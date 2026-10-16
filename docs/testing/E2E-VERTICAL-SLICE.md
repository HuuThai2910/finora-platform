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

## 2. Hạ tầng Docker

```powershell
# Kafka, Keycloak, Redis User, User, AI (profile demo) và Fineract
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract up -d
# Khi finora-user hoặc finora-common đổi code: build lại container User (launcher sẽ chặn nếu image cũ hơn commit)
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo up -d --build --no-deps user
```

`blockchain-postgres` được launcher bật tự động. `docker/.env` hiện để trống mật khẩu của container này nên
launcher sinh mật khẩu local một lần trong `docker/e2e/out/.env.e2e` (git-ignore) và dùng lại cho các lần sau.
Payment chỉ có dependency Redis nhưng không có code dùng Redis, nên không cần bật `payment-redis`.

## 3. Build và chạy sáu service Java

```powershell
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Start -Build
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Status
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Stop
```

Launcher khởi động lần lượt Loan, Payment, Investment, Notification, Blockchain, Gateway từ jar, đọc `.env`
của từng module như IntelliJ và chỉ ghi đè các biến E2E: `SPRING_PROFILES_ACTIVE=local`,
`FINORA_SIGNATURE_PROVIDER=MOCK`, `FINERACT_BOOKING_PROVIDER=fineract`, `PAYMENT_*_PROVIDER=mock`,
`INVESTMENT_AUTO_INVEST_ENABLED=false`, `INVESTMENT_REQUIRE_EKYC=false`, Blockchain listener/worker bật với
provider `MOCK`. Cổng đang bị IntelliJ chiếm thì launcher dừng với thông báo rõ. Log nằm ở `docker/e2e/out/logs`.

Khi chạy code hiện hành, Flyway của từng service áp migration còn thiếu lên DB dev dùng chung. Lần chạy
ngày 2026-10-17 đã đưa Loan lên V27, Investment lên V15, Payment lên V11.

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

Mỗi lần chạy tạo tài khoản mới `e2e.borrower.<run>@finora.test` và `e2e.investor.<run>@finora.test`, nên
không vướng giới hạn 3 OTP/giờ và không cần xóa dữ liệu. Product dùng mã `E2E_FIX12_<6 hex của tên máy>`
vì mapping Product↔Fineract nằm trong DB Loan dùng chung còn Fineract là container riêng của từng máy.

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
| Trả kỳ | Số tiền đúng template Fineract; `COMPLETED`; borrower trừ một lần; một giao dịch `FINORA-REPAY-<id>`; `principal + interest + fee + penalty = amount` | Payment + Fineract |
| Phân bổ | Investor nhận đủ gốc + lãi; portfolio cập nhật; Loan projection giảm dư nợ; thông báo `REPAYMENT_CREDITED`; proof `CONFIRMED` (MOCK) | Payment + Investment + Loan + Notification + Blockchain |
| Kafka | Mỗi topic của luồng có đúng một `eventId` cho business key | Kafka |
| Drill | Phát lại event không tạo Note/tiền/thông báo/proof thứ hai | Investment + Payment + Notification + Blockchain |

## 6. Evidence

`docker/e2e/out/<runId>.json` gồm `environment` (dependency thật/mock), `accounts`, `product`, `application`,
`decision`, `listing`, `funding`, `contract`, `disbursement`, `repayment`, `kafka` (event ID theo topic),
`drills`, `checks` và `defects`. Không có token, PIN, OTP hay mật khẩu. Lần chạy thất bại vẫn lưu evidence với
`result = FAIL` và `failureReason`.

## 7. Giới hạn đã biết

- Ca restart giữa saga, Fineract timeout, sáu fixture servicing và test clock thuộc R1B.
- Nhánh bù trừ `LoanFundingCancelled` được chứng minh bằng integration test Loan/Investment; drill hết hạn ký
  trên stack thật cần test clock (R1B).
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
| `blockchain-postgres không healthy` | Script init có CRLF trong working copy; `.gitattributes` yêu cầu LF cho `*.sh` |
| `Cổng 808x đang bị tiến trình khác dùng` | Dừng service chạy từ IntelliJ trước khi chạy launcher |
| Lệnh gọi launcher không bao giờ kết thúc | Không pipe output của launcher (`\| Select-Object`, `\| Tee-Object`): JVM con giữ handle stdout nên pipeline không nhận EOF; chạy launcher trực tiếp |
| `OTP đăng ký … hết 30 giây` | Redis User không phải `finora-user-redis` hoặc User không ghi OTP |
| Trả nợ `400 VALIDATION_FAILED` | Gửi số tiền quá 2 chữ số thập phân (Fineract trả scale 6) |
| Đặt lệnh `400 RISK_ACKNOWLEDGEMENT_REQUIRED` hoặc `409 RISK_DISCLOSURE_OUTDATED` | Thiếu hoặc lệch version xác nhận rủi ro so với cấu hình Investment |
| Nộp hồ sơ `422 BORROWER_EXPOSURE_LIMIT_EXCEEDED` | Người vay E2E dùng lại tài khoản cũ còn hồ sơ mở; harness tạo tài khoản mới mỗi lần |
| `PENDING_REVIEW nhưng assessment=FAILED` | AI không phản hồi; xem log Loan và container `finora-ai` |
