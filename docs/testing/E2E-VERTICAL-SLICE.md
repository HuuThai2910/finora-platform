# Runbook E2E — vertical slice tự động (R1A)

Runbook này chạy một hành trình đầy đủ bằng API thật: borrower đăng ký, nộp hồ sơ, AI chấm, admin duyệt,
nhà đầu tư nạp ví mock và góp vốn, hai bên ký, saga giải ngân qua Fineract, phát hành Note, borrower trả
một kỳ và nhà đầu tư nhận phân bổ. Mục tiêu là baseline lặp lại được (R1A); cổng nghiệm thu “core ổn định”
là R1B sau R2/R3. Task plan: [`R1A-e2e-vertical-slice-baseline.md`](../../.agents/plans/R1A-e2e-vertical-slice-baseline.md).

## 1. Thật, sandbox hay mock

| Thành phần | Trong lần chạy | Không được hiểu là |
|---|---|---|
| User/Keycloak, Loan, Investment, Payment ledger, Notification, Kafka | Code hiện hành, dữ liệu thật trên DB dev | — |
| Fineract | Fineract 1.15 local thật (lịch trả, booking, repayment) | Core dùng chung giữa các máy |
| AI | Container AI v17 thật; CIC Node đã deploy | Có dữ liệu CIC: borrower chưa eKYC nên rule CIC thiếu dữ liệu |
| Chữ ký | `MOCK + CLICK_WRAP_MVP` | SmartCA hoặc PDF có chữ ký PAdES |
| Nạp ví, giải ngân ra ngoài | Provider `mock` | ZaloPay/ngân hàng, tiền thật |
| Blockchain | Proof store thật, ledger `MOCK` | Fabric |
| KYC người vay trong Loan | Profile mock VERIFIED (profile `local`) | eKYC thật (adapter thuộc Wave 2) |
| eKYC nhà đầu tư | Tắt bằng `INVESTMENT_REQUIRE_EKYC=false` chỉ trong stack E2E | Bỏ kiểm tra eKYC ở môi trường khác |
| OTP đăng ký | Đọc `reg_otp:<email>` trong Redis User local | Email OTP |
| Rút tiền về ngân hàng (W3B) | Provider payout `mock`: nhận lệnh, gọi callback HTTP thật có chữ ký HMAC tới Payment; tài khoản kết thúc `0000` mô phỏng tài khoản sai | Chuyển khoản ngân hàng, tiền thật |
| Xác thực giữa service (W3A) | Client credentials Keycloak thật: `finora-loan-client` → AI, `finora-user-client` → AI OCR và Notification, `finora-ai-client` → User | — |

## 2. Hạ tầng Docker

```powershell
# Kafka, Keycloak, Redis User, User, AI (profile demo) và Fineract
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract up -d
# Khi finora-user hoặc finora-common đổi code: build lại container User (launcher sẽ chặn nếu image cũ hơn commit)
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo up -d --build --no-deps user
```

Từ Wave 3 (W3A) realm `finora` phải có client service và role mới trước lần chạy đầu. Chạy một lần cho mỗi realm (cần mật
khẩu admin master của Keycloak; script không in secret, tự ghi secret vào `docker/.env`, `finora-loan/.env`, `finora-ai/.env`
git-ignore), rồi tạo lại container AI và User để nhận image/secret mới:

```bash
KEYCLOAK_ADMIN_PASSWORD='<mật khẩu admin master>' bash docker/keycloak/add-service-clients.sh   # thêm --skip-brute-force nếu realm dùng chung
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile ai up -d --build --no-deps ai
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo up -d --build --no-deps user
```

Thiếu bước này thì AI trả `401/403` cho Loan (chấm điểm đứng ở hàng đợi retry) và User không gửi được email qua Notification.

`blockchain-postgres` được launcher bật tự động. `docker/.env` hiện để trống mật khẩu của container này nên
launcher sinh mật khẩu local một lần trong `docker/e2e/out/.env.e2e` (git-ignore) và dùng lại cho các lần sau.
Payment chỉ có dependency Redis nhưng không có code dùng Redis, nên không cần bật `payment-redis`.

## 3. Build và chạy sáu service Java

```powershell
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Start -Build
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Status
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Stop
# Chạy lại đúng một service (ví dụ sau khi build lại jar); các service khác giữ nguyên
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Restart -Services payment
```

Launcher từ chối bật service khi jar cũ hơn file mới nhất trong `src/main`/`pom.xml` của module, `finora-common` hoặc
pom gốc (B65): evidence trên jar cũ cho kết quả sai, ví dụ route Gateway mới trả `404`. Thêm `-Build` để build lại;
`-AllowStaleJar` chỉ dùng khi cố ý chạy lại bản build trước.

`-PaymentClockOffset P<n>D` và `-LoanClockOffset P<n>D` (chỉ harness dùng, Q12) chạy Payment/Loan với đồng hồ lệch n
ngày; Payment từ chối khởi động nếu provider giải ngân hoặc nạp tiền không phải `mock`, Loan từ chối nếu chữ ký không
phải `MOCK` hoặc profile không phải `local`. `Status` hiện cột `clock` (`thật` hoặc độ lệch) và `database`.

`-Database local` (mặc định từ Wave 2D, D24) chạy Loan, Payment, Investment, Notification trên bốn container PostgreSQL
local riêng (`finora-loan-postgres` 15433, `finora-payment-postgres` 15434, `finora-investment-postgres` 15437,
`finora-notification-postgres` 15438). Lần đầu launcher sinh mật khẩu ngẫu nhiên vào `docker/e2e/out/.env.e2e` (thư mục
`out/` bị git-ignore, không in mật khẩu ra màn hình) và đặt `FINERACT_PRODUCT_NAMESPACE=E2E` cho Loan, nên Product E2E
trên Fineract có externalId `FINORA-E2E-LP-*`, tên và shortName riêng, không trùng Product của DB dev. `-Database shared`
dùng lại DB dev dùng chung trong `.env` của từng module; khi đó không được dời đồng hồ. `-Action Restart` từ chối nếu
khác chế độ DB của lượt `Start`.

Launcher khởi động lần lượt Loan, Payment, Investment, Notification, Blockchain, Gateway từ jar, đọc `.env`
của từng module như IntelliJ và chỉ ghi đè các biến E2E: `SPRING_PROFILES_ACTIVE=local`,
`FINORA_SIGNATURE_PROVIDER=MOCK`, `FINERACT_BOOKING_PROVIDER=fineract`, `PAYMENT_*_PROVIDER=mock`,
`INVESTMENT_AUTO_INVEST_ENABLED=false`, `INVESTMENT_REQUIRE_EKYC=false`, Blockchain listener/worker bật với
provider `MOCK`, `FINORA_BORROWER_PROFILE_PROVIDER=mock` (người vay E2E chưa eKYC; đổi bằng
`-BorrowerProfileProvider user`). Cổng đang bị IntelliJ chiếm thì launcher dừng với thông báo rõ. Log nằm ở
`docker/e2e/out/logs`.

Flyway của từng service áp migration còn thiếu lên DB đang dùng. Với `-Database local` chỉ DB local đổi; sau Wave 2D
(2026-10-17) DB local ở Loan V31, Payment V17, Investment V17. DB dev dùng chung vẫn ở mức Wave 2C (Loan V30,
Investment V16, Payment V15) cho tới khi có người chạy code mới với `-Database shared` hoặc từ IntelliJ.

## 4. Chạy kịch bản

```powershell
# Tài khoản admin demo; nếu không đặt, script đọc tài khoản demo công bố trong finora-web DemoAccounts.tsx
$env:FINORA_E2E_ADMIN_EMAIL = 'admin@finora.vn'
$env:FINORA_E2E_ADMIN_PASSWORD = '<mật khẩu demo>'
powershell -ExecutionPolicy Bypass -File docker/e2e/run-vertical-slice.ps1 -Drills
```

| Tham số | Ý nghĩa |
|---|---|
| `-Drills` | Phát lại đúng record Kafka `LoanDisbursed` và `RepaymentDistributed` để kiểm consumer chống trùng |
| `-SkipRepayment` | Dừng sau khi giải ngân và phát hành Note |
| `-KeepProductActive` | Không ngừng Product E2E sau khi chạy (mặc định ngừng để borrower thật không thấy) |
| `-LoanAmount`, `-TermMonths` | Mặc định 10.000.000 VND, 6 tháng; số tiền phải chia hết mệnh giá Note |
| `-Servicing` | Cơ chế servicing sau giải ngân (R1B): `Installment` (mặc định: trả kỳ 1 trước hạn, kiểm giữ/hủy/chặn, báo giá tất toán, dời đồng hồ tới ngày đến hạn — mục 4a), `PartialPrepayment` (khoản kỳ gần nhất + 3.000.000 gốc), `EarlySettlement` (tất toán, người vay nạp mock phần phí thiếu), `OverdueCure` (quá hạn 10 ngày, nhóm 2, rồi khắc phục), `DefaultCure` (10 → 91 ngày nợ xấu → trả bớt kỳ cũ nhất, vẫn `DEFAULTED` → khắc phục toàn bộ → 181 → 361 ngày → trả hết, `SETTLED`), `Reschedule` (người vay xin gia hạn 2 kỳ, admin thẩm định, nhà đầu tư đồng ý, Fineract đổi lịch). Ba fixture cuối cần `-Database local`. Từ Wave 2C, lãi tương lai là check phải đạt (`prepayment.no-future-interest`, `settlement.no-future-interest`); từ Wave 2D số khắc phục phải phủ toàn bộ quá hạn (`delinquency.<mốc>-quote-covers-overdue`, B31) |
| `-KycGate` | Chỉ kiểm cổng eKYC (W2-KYC): người vay mới đọc `/users/me/kyc-summary` qua Gateway và bị Loan từ chối `409 BORROWER_KYC_REQUIRED`, không có hồ sơ nào được tạo. Cần launcher chạy với `-BorrowerProfileProvider user` |

Mỗi lần chạy tạo tài khoản mới `e2e.borrower.<run>@finora.test` và `e2e.investor.<run>@finora.test`, nên
không vướng giới hạn 3 OTP/giờ và không cần xóa dữ liệu. Product dùng mã `E2E_V4_FIX12_<6 hex của tên máy>`
(core V4 tính lãi theo ngày; mã V3 cũ `E2E_FIX12_*` không dùng nữa vì Product đã đồng bộ không đổi cấu hình được)
vì mapping Product↔Fineract nằm trong DB Loan dùng chung còn Fineract là container riêng của từng máy.

## 4a. Đồng hồ E2E (Q12)

Khoản trả kỳ trước hạn trên khoản vay core V4 được Payment giữ tới ngày đến hạn (D16). Để kiểm mà không chờ một tháng,
bước `Installment` của harness:

1. Bật `enable-business-date` của Fineract local và đặt `BUSINESS_DATE` = ngày đến hạn (helper
   `Invoke-E2EFineractWrite` chỉ cho ghi `/businessdate` và `/configurations/<id>`).
2. Chạy lại Payment bằng launcher với `-PaymentClockOffset P<n>D` (n = số ngày tới ngày đến hạn).
3. Chờ worker ghi Fineract đúng ngày đến hạn và phân phối, rồi trong `finally` trả Fineract về hôm nay, tắt business
   date và chạy lại Payment với đồng hồ thật.

Từ Wave 2D, `Move-E2EClock -Date <yyyy-MM-dd> -Services loan,payment` dời cả Loan và Payment (chỉ khi `-Database
local`, tối đa 999 ngày) và giả lập EOD của Fineract (D25): dời ngày tới thì chạy "Recalculate Interest For Loans"
(`LA_RINT`, đưa lãi trên gốc quá hạn vào lịch) rồi "Update Loan Arrears Ageing" (`LA_ARAG`, cập nhật `summary.totalOverdue`);
trả về hôm nay chỉ chạy `LA_ARAG`. Production chạy hai job này lúc 00:01. Helper ghi Fineract chỉ cho `/businessdate`,
`/configurations/<id>` và `/jobs/<id>?command=executeJob`.

Preflight và cuối mỗi lượt đều gọi `Reset-E2EClock`, nên lượt bị ngắt giữa chừng được dọn ở lượt sau. Trong cửa sổ đồng
hồ lệch, PIN vẫn kiểm theo giờ UTC thật. Sau khi trả đồng hồ, khoản vay của lượt có giao dịch đề ngày đến hạn (tương lai
so với hôm nay), nên harness kiểm báo giá tất toán trước khi dời đồng hồ; không dùng khoản vay đó cho thao tác khác.
Không có API đổi ngày ở demo/production: offset chỉ đọc lúc Payment khởi động.

## 4b. Drill lỗi thật (Q13)

```powershell
# Một lần: bật stack với Toxiproxy (Payment → Fineract đi qua finora-toxiproxy:18444, profile e2e-faults)
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Start -WithToxiproxy
# Mất phản hồi / mất request khi Payment ghi kỳ trả nợ vào Fineract (đi cùng bước trả kỳ mặc định)
powershell -ExecutionPolicy Bypass -File docker/e2e/run-vertical-slice.ps1 -FaultDrill FineractResponseLost
powershell -ExecutionPolicy Bypass -File docker/e2e/run-vertical-slice.ps1 -FaultDrill FineractRequestLost
# Tắt hẳn JVM giữa giải ngân (không cần Toxiproxy)
powershell -ExecutionPolicy Bypass -File docker/e2e/run-vertical-slice.ps1 -FaultDrill LoanCrashDuringBooking -SkipRepayment
powershell -ExecutionPolicy Bypass -File docker/e2e/run-vertical-slice.ps1 -FaultDrill PaymentCrashDuringDisbursement -SkipRepayment
powershell -ExecutionPolicy Bypass -File docker/e2e/run-vertical-slice.ps1 -FaultDrill InvestmentDownDuringDisbursement -SkipRepayment
```

| Drill | Cách tiêm lỗi | Kết quả phải đạt |
|---|---|---|
| `FineractResponseLost` | Toxic `timeout` chiều Fineract → Payment: Fineract ghi giao dịch nhưng Payment hết thời gian chờ | `RECONCILIATION_REQUIRED` với `errorCode = FINERACT_REPAYMENT_OUTCOME_UNKNOWN` (B42) → worker đối soát tìm thấy theo externalId → `COMPLETED`; Fineract một giao dịch |
| `FineractRequestLost` | Toxic `reset_peer` chiều Payment → Fineract: request không tới Fineract | `RECONCILIATION_REQUIRED` với `FINERACT_REPAYMENT_OUTCOME_UNKNOWN`, đối soát không thấy; sau khoảng an toàn (drill đặt 1 phút) admin `POST .../repost` → `COMPLETED`, `coreRepostCount=1`; Fineract một giao dịch (B32) |
| `LoanCrashDuringBooking` | Loan dừng ở `core-booked-before-save` (Fineract đã ghi khoản vay), harness `Stop-Process -Force` rồi bật lại với lease 30 giây | Saga được nhận lại (`attemptCount >= 2`), đối chiếu theo số hợp đồng, Fineract đúng một khoản vay |
| `PaymentCrashDuringDisbursement` | Payment dừng ở `provider-disbursed-before-save` rồi bị tắt hẳn, bật lại với lease 30 giây | Hết lease → đối soát provider theo `sagaId` → `COMPLETED`; ví người vay cộng đúng một lần; `GET /admin/disbursement-reconciliation/{sagaId}` có `attemptCount = 1` (provider chỉ được gọi chuyển tiền một lần) và `errorCode = PAYMENT_PROCESSING_INTERRUPTED` |
| `InvestmentDownDuringDisbursement` | Investment bị tắt khi Loan dừng trước khi phát `LoanDisbursed` | Bật lại Investment, consumer đọc tiếp offset: đủ Note, vị thế có ngày đáo hạn, không Note thứ hai |

Điểm dừng chỉ nhận khi chữ ký `MOCK`/provider tiền `mock` và profile `local` (service từ chối khởi động nếu không).
Launcher chỉ nhận `-DrillEnv` với các khóa trong danh sách cho phép (điểm dừng, lease, khoảng an toàn ghi lại). Provider giải
ngân giả lập trả kết quả tất định theo `sagaId`, nên drill Payment chứng minh lease, đối soát và ledger idempotent của Payment,
không chứng minh hành vi của một provider thật.

## 5. Kết quả mong đợi theo nguồn dữ liệu chuẩn

| Bước | Kiểm tra trong script | SoR |
|---|---|---|
| Nộp hồ sơ | `201`; gửi lại cùng khóa trả cùng `applicationNumber`; cùng khóa khác nội dung `409 IDEMPOTENCY_KEY_REUSED` | Loan |
| Chấm điểm/duyệt | `PENDING_REVIEW` (thiếu dữ liệu CIC) → admin `POLICY_APPROVED`; replay duyệt trả `200` | Loan + AI |
| Điều khoản | Product lãi cố định nên `AUTO_AUTHORIZED`; nếu `PENDING` script chấp nhận đúng version/hash | Loan |
| Listing | `OPEN`, target = số vay, lãi 12% | Investment |
| Nạp ví | `MOCK`, cộng đúng một lần; replay cùng khóa cùng `topUpId` | Payment ledger |
| Đặt lệnh | `COMMITTED`; available giảm và held tăng đúng số lệnh; replay không giữ tiền lần hai | Investment + Payment |
| Hợp đồng | Investor và borrower tải cùng PDF (SHA-256 khớp `pdfDocumentHash`); lender ký, replay không ký lần hai; borrower ký → `EFFECTIVE` | Loan |
| Giải ngân | Saga `COMPLETED`; borrower +số vay; held investor về 0; Fineract `loanStatusType.active`; 10 Note `ACTIVE` | Loan + Payment + Fineract + Investment |
| Trả kỳ trước hạn | `scheduled-due.postOn` = ngày đến hạn kỳ 1; lệnh đầu `COLLECTED` + `postOn`, Fineract chưa có giao dịch; trả lần hai `409 INSTALLMENT_ALREADY_PAID_PENDING`; báo giá tất toán `409 INSTALLMENT_PENDING_POSTING`; hủy → `CANCELLED`, ví về số cũ | Payment + Fineract |
| Trả kỳ | Sau khi dời đồng hồ: `COMPLETED`; borrower trừ đúng một lần (tính cả lệnh đã hủy); một giao dịch `FINORA-REPAY-<id>` đề đúng ngày đến hạn; gốc/lãi bằng lịch kỳ 1; `principal + interest + fee + penalty = amount` | Payment + Fineract |
| Phân bổ | Investor nhận đủ gốc + lãi; portfolio cập nhật; Loan projection giảm dư nợ; thông báo `REPAYMENT_CREDITED`; proof `CONFIRMED` (MOCK) | Payment + Investment + Loan + Notification + Blockchain |
| Báo giá tất toán | `policyVersion` là biểu phí in trong hợp đồng (`AGRIBANK_REFERENCE_FINORA_2026_V2`), phí = max(gốc × tỷ lệ, 100.000), nhà đầu tư 80%; chỉ lập báo giá, không chuyển tiền | Payment + Loan (hợp đồng) |
| Kafka | Mỗi topic của luồng có đúng một `eventId` cho business key | Kafka |
| Drill | Phát lại event không tạo Note/tiền/thông báo/proof thứ hai | Investment + Payment + Notification + Blockchain |

## 5b. Kiểm bảo mật và rút tiền (Wave 3)

| Check | Ý nghĩa |
|---|---|
| `security.pin-token-bound-to-one-action` | Token PIN của lệnh trả kỳ dùng cho một `Idempotency-Key` khác bị `403 PIN_TOKEN_INVALID` (D38) |
| `access.other-borrower.*`, `access.other-investor.*` | Người dùng thứ hai cùng vai trò đổi ID không đọc được hồ sơ, hợp đồng, PDF, khoản vay, khoản trả, khoản giữ của người thứ nhất (`403/404`) |
| `access.borrower-not-admin.*`, `access.investor-not-admin.*` | Token người dùng gọi API quản trị nhận `403` |
| `access.anonymous.*`, `access.borrower.ai-scoring` | AI (trực tiếp và qua Gateway) và endpoint nội bộ Notification từ chối lời gọi không token (`401`); token người vay không chấm điểm được (`403`) |
| `gateway.ai-post-body-forwarded` | Admin gọi AI qua Gateway, FastAPI báo thiếu từng field: body POST tới nơi (B43) |
| `withdrawal.*` | Chỉ ở fixture trả kỳ mặc định: policy, dưới mức tối thiểu bị chặn, trừ ví đúng một lần khi gửi lại, số tài khoản che, `COMPLETED` qua callback ký HMAC, tài khoản sai `FAILED` và hoàn tiền, vượt hạn mức ngày bị chặn, hàng đợi admin |

## 6. Evidence

`docker/e2e/out/<runId>.json` gồm `environment` (dependency thật/mock), `accounts`, `product`, `application`,
`decision`, `listing`, `funding`, `contract`, `disbursement`, `repayment`, `kafka` (event ID theo topic),
`drills`, `faultDrill`, `checks` và `defects`. Không có token, PIN, OTP hay mật khẩu. Lần chạy thất bại vẫn lưu evidence
với `result = FAIL` và `failureReason`. Log service của lần chạy hiện tại ở `docker/e2e/out/logs/<service>.log`; mỗi lần
launcher bật lại một service, log cũ chuyển sang `out/logs/archive/<service>-<yyyyMMdd-HHmmss>.log` (giữ 10 bản), nên log
của JVM bị drill tắt vẫn còn.

## 7. Giới hạn đã biết

- Fixture 2/5/6 (`OverdueCure`, `Reschedule`, `DefaultCure`) và drill lỗi thật (mục 4b) chạy từ Wave 2D. Chưa có drill
  chuyển nhượng Note cạnh trả nợ (cần chợ Notes trong harness, R11).
- Nhánh bù trừ `LoanFundingCancelled` được chứng minh bằng integration test Loan/Investment; chưa có drill hết hạn ký
  trên stack thật (đồng hồ Loan đã có từ Wave 2D, R1B).
- Toxiproxy chỉ đặt giữa Payment và Fineract; lỗi mạng Loan → Fineract và Investment → Payment chưa có drill.
- DB Loan dùng chung chứa khoản vay của máy khác mà Fineract local không có: worker sync ghi một dòng `warn` cho mỗi
  khoản không đồng bộ được (B21). Không ảnh hưởng khoản vay của lần chạy; tách DB theo môi trường thuộc R9.
- Ngày nghiệp vụ Loan/Payment là `Asia/Ho_Chi_Minh` từ Wave 2 (`FINORA_BUSINESS_TIME_ZONE`); chạy từ 00:00 đến 07:00
  giờ Việt Nam không còn lệch ngày `R1A-BIZDATE`.
- Lệnh góp vốn phải gửi `riskAcknowledgementVersion` khớp `INVESTMENT_RISK_DISCLOSURE_VERSION` (mặc định
  `INVESTOR_RISK_V1`); harness gửi sẵn giá trị này.

## 8. Xử lý sự cố

| Triệu chứng | Nguyên nhân thường gặp |
|---|---|
| `Container finora-user tạo lúc … cũ hơn commit` | Build lại container User (mục 2) |
| `Jar finora-… cũ hơn …` | Mã nguồn đã sửa sau lần build jar; chạy launcher với `-Build` (B65) |
| `blockchain-postgres không healthy` | Script init có CRLF trong working copy; `.gitattributes` yêu cầu LF cho `*.sh` |
| `Cổng 808x đang bị tiến trình khác dùng` | Dừng service chạy từ IntelliJ trước khi chạy launcher |
| Lệnh gọi launcher không bao giờ kết thúc | Không pipe output của launcher (`\| Select-Object`, `\| Tee-Object`): JVM con giữ handle stdout nên pipeline không nhận EOF; chạy launcher trực tiếp |
| `OTP đăng ký … hết 30 giây` | Redis User không phải `finora-user-redis` hoặc User không ghi OTP |
| Lượt trước bị ngắt khi đang dời đồng hồ (Fineract còn business date, `Status` hiện `clock=P31D`) | Lượt chạy kế tiếp tự trả về ngày thật ở preflight. Làm tay: `-Action Restart -Services payment` rồi `PUT /configurations/<id của enable-business-date>` với `{"enabled": false}` trên Fineract local |
| Payment dừng ngay khi khởi động với `finora.e2e.clock-offset chỉ dùng khi provider…` | Đang bật đồng hồ E2E cùng provider tiền khác `mock`; bỏ `-PaymentClockOffset` hoặc dùng provider mock |
| Trả kỳ trả `409 INSTALLMENT_ALREADY_PAID_PENDING` | Đã có khoản trả kỳ trước hạn đang giữ; hủy (`POST /repayments/{id}/cancel`) hoặc chờ ngày đến hạn |
| Trả nợ `400 VALIDATION_FAILED` | Gửi số tiền quá 2 chữ số thập phân (Fineract trả scale 6) |
| Đặt lệnh `400 RISK_ACKNOWLEDGEMENT_REQUIRED` hoặc `409 RISK_DISCLOSURE_OUTDATED` | Thiếu hoặc lệch version xác nhận rủi ro so với cấu hình Investment |
| Nộp hồ sơ `422 BORROWER_EXPOSURE_LIMIT_EXCEEDED` | Người vay E2E dùng lại tài khoản cũ còn hồ sơ mở; harness tạo tài khoản mới mỗi lần |
| `PENDING_REVIEW nhưng assessment=FAILED` | AI không phản hồi; xem log Loan và container `finora-ai` |
| Chữ tiếng Việt trong log của lượt chạy tách cửa sổ bị lỗi font, không tìm được "Kết quả" | Console Windows ghi theo code page cũ; đọc `docker/e2e/out/<runId>.json` (UTF-8) thay cho log |
| DPD của Loan đứng ở 0 sau khi dời đồng hồ | Job `LA_ARAG` chưa chạy nên Fineract chưa cập nhật `totalOverdue`; harness chạy job sau mỗi lần đặt ngày, làm tay bằng `POST /jobs/<id của LA_ARAG>?command=executeJob` trên Fineract local |
| Trả đủ số khắc phục ở khoản vay quá ngày đáo hạn mà vẫn còn quá hạn vài chục nghìn | Job `LA_RINT` chưa chạy nên lãi sau đáo hạn chưa vào nghĩa vụ; harness chạy job khi dời ngày, ở production đây là job cuối ngày |
| Harness không thấy consent cơ cấu của nhà đầu tư | Investment trả trang có field `content` (PageResponse chung), Loan trả `data` (B33) |
| `Move-E2EClock` báo chỉ dùng với DB local | Stack đang chạy `-Database shared`; dừng và chạy lại `-Action Start -Database local` |
| `Drill Fineract... cần stack chạy với -WithToxiproxy` | Dừng stack rồi `-Action Start -WithToxiproxy`; các lần `Restart` Payment sau đó tự giữ đường đi qua proxy |
| `Toxiproxy chưa trả lời API` dù container healthy | Toxiproxy trả `403 User agent not allowed` cho User-Agent "Mozilla..." mặc định của PowerShell 5.1; gọi API bằng `-UserAgent` khác hoặc `curl` |
| Lượt drill bị ngắt, Payment không gọi được Fineract | Còn toxic trên proxy: lượt sau tự gỡ ở preflight; làm tay `DELETE http://localhost:8474/proxies/fineract-payment/toxics/q13-drill` |
