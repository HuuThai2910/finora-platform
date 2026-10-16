---
task_id: FINORA-REMAINING
title: Hoàn thiện FINORA sau vertical slice giải ngân và servicing
owner: Both
status: ACTIVE
depends_on: [P5, P6]
created_at: 2026-10-16
updated_at: 2026-10-17
---

# FINORA-REMAINING — Kế hoạch chi tiết phần việc còn lại

## Bản đọc nhanh theo nghiệp vụ

### Hệ thống đã làm được gì?

FINORA đã có vertical slice thật cho đăng nhập/eKYC, nộp và chấm điểm hồ sơ, duyệt, gọi vốn,
giữ tiền, hợp đồng nhiều bên, SmartCA UAT, giải ngân, tạo Note, trả nợ, trả trước, tất toán,
quá hạn/default/cure và yêu cầu cơ cấu có nhà đầu tư biểu quyết. Web quản trị và mobile mới đã
kết nối các API chính; backend không còn là skeleton.

### Còn thiếu gì để demo ổn định?

Ưu tiên không phải thêm một luồng nghiệp vụ lớn, mà là biến luồng hiện có thành bộ demo có thể
lặp lại: dựng môi trường bằng một quy trình, tạo dữ liệu sạch, chạy từ đầu đến một kỳ trả nợ,
chứng minh retry/restart không làm trùng tiền, sửa projection/Note khi event đến trễ và có màn hình
đối soát rõ ràng.

### Còn thiếu gì nếu tiến gần production?

- Cổng tiền ra/rút tiền thật; hiện top-up có mock/ZaloPay sandbox nhưng disbursement ZaloPay chưa chạy thật.
- Chữ ký PAdES nhúng vào PDF; hiện SmartCA UAT ký hash và lưu bằng chứng rời.
- Fabric adapter/network/chaincode thật và proof cho nhiều mốc ngoài repayment.
- Push notification thật, RBAC/PII/security hardening, observability và deployment hoàn chỉnh.
- Model governance, fraud nâng cao, secondary market/auto-invest hardening.

### Điều đã chốt và không đưa lại vào backlog

- CIC là service Node deploy tại `https://cic-service-node.vercel.app/`; FINORA chỉ cấu hình URL.
- Mobile phải dùng bộ UI mới, không phục hồi màn mock/UI cũ.
- Fineract là nguồn chuẩn lịch trả, nghĩa vụ và breakdown; frontend không tự tính lại tiền.
- Payment ledger là nguồn chuẩn dịch chuyển tiền nội bộ; Investment là nguồn chuẩn Note/ownership.
- Một PDF hợp đồng dùng chung cho các bên; lender ký trước, borrower ký sau.

## 1. Current state và phạm vi

### 1.1 Nguồn sự thật

| Phạm vi | Nguồn sự thật | Không được làm |
|---|---|---|
| Identity/eKYC | User + Keycloak; CIC qua REST ngoài Compose | Không đọc DB User/CIC chéo |
| Hồ sơ, hợp đồng, servicing | Loan + Fineract projection | Không tính lịch/lãi/phí ở web/mobile |
| Tiền, hold, thu/chi | Payment ledger/provider reference | Không sửa số dư trực tiếp |
| Listing, allocation, Note | Investment | Không suy luận ownership từ Loan |
| Bằng chứng | Blockchain hash proof | Không lưu PII/PDF lên chain |
| Thông báo | Notification delivery projection | Không để lỗi thông báo rollback tiền |

### 1.2 Ngoài phạm vi của plan tổng hợp

Plan này không tự thêm entity/API/event. Mỗi workstream khi bắt đầu phải có task plan riêng theo
`09-planning-documentation.md`, mô tả field, ERD/cardinality, query/index, transaction, concurrency,
idempotency và acceptance evidence. CIC Node được xem là dependency bên ngoài, không triển khai lại.

## 2. Thứ tự triển khai bắt buộc

```text
R0 Đồng bộ source và tài liệu
 → R1A chạy baseline E2E, ghi nhận sai lệch và khóa G-POLICY
 → R2 Saga/Fineract/financial recovery || R3 Investment projection/reconciliation
 → R1B chạy lại E2E và toàn bộ regression servicing
 → R8A security gate tối thiểu
 → R4 tiền ngoài hệ thống
 → R5 PDF || R6 Blockchain || R7 Notification
 → R8B security hardening || R9 observability/deployment
 → R10 AI || R11 secondary market/auto-invest nâng cao
```

`||` nghĩa là hai workstream có thể chạy song song nếu không sửa cùng contract/vùng ownership. R1A
không đòi mọi fault test phải xanh: mục tiêu là dựng baseline lặp lại và ghi defect có evidence. R1B chỉ
được nghiệm thu sau R2/R3, nhờ đó không tạo vòng phụ thuộc “phải sửa xong mới được phép tìm lỗi”.

R1B–R3 và G-POLICY là cổng trước khi mở rộng R4–R7. R8A là cổng riêng trước mọi provider tiền/chữ ký
ngoài hệ thống. Không gọi một tính năng là hoàn tất chỉ vì UI trả `200`; phải kiểm tra state tại đúng
SoR, ledger/event và khả năng retry.

## 3. Danh mục tồn đọng

| ID | Ưu tiên | Owner | Phạm vi | Trạng thái | Kết quả cần đạt |
|---|---|---|---|---|---|
| R0 | P0 | Both | Source/docs | `DONE` | Backend, web, mobile cùng mốc code; roadmap/rule khớp code |
| R1 | P0 | Both | E2E | `IN_PROGRESS` | R1A baseline tự động đã chạy thật ngày 2026-10-17; R1B chạy sạch core và toàn bộ servicing sau R2/R3 |
| G-POLICY | P0 | Both | Policy/Contract | `IN_PROGRESS` | Khoản vay cũ/mới dùng đúng policy version và hợp đồng/quote/UI cùng một công thức; Wave 2B xong biểu phí trả trước (`G-POLICY-W2`) |
| R2 | P0 | Thai | Loan/Payment/Fineract | `IN_PROGRESS` | Restart/timeout/duplicate không giải ngân hoặc thu tiền hai lần; Wave 2 xong B06–B13 (`R2-W2` chờ review) |
| R3 | P0 | Hai | Investment | `IN_PROGRESS` | Order–hold, Note activation, ownership và portfolio tự repair; Wave 2 xong B14–B16 (`R3-W2` chờ Hải review) |
| R4 | P1 | Thai | Payment | `BACKLOG` | Rút tiền và provider disbursement sandbox chạy thật, đối soát được |
| R5 | P1 | Thai + Hai | Loan/Investment/SmartCA | `BACKLOG` | PDF có chữ ký nhúng của nhiều bên hoặc quyết định giữ receipt rời được duyệt |
| R6 | P1 | Thai | Blockchain | `BACKLOG` | Fabric thật + proof/query/reconcile cho các mốc đã duyệt |
| R7 | P1 | Hai | Notification | `IN_PROGRESS` | Template đầy đủ và push thật; lỗi provider không làm sai nghiệp vụ |
| R8 | P1 | Both | Security | `BACKLOG` | RBAC/resource auth, PII/secret/log và abuse controls được kiểm chứng |
| R9 | P1 | Both | Ops | `BACKLOG` | Health, metrics, alert, replay/DLT và deployment tái lập được |
| R10 | P2 | Hai | AI | `BACKLOG` | Backtest/champion-challenger, fraud và early warning có metric |
| R11 | P2 | Hai + Thai | Investment/Payment | `IN_PROGRESS` | Secondary market/auto-invest qua concurrency/reconciliation E2E |

## 3a. Quyết định owner ngày 2026-10-17

Thái chốt trong phiên rà soát ngày 2026-10-17 và cho phép agent chọn phương án được khuyến nghị cho các
lựa chọn kỹ thuật còn lại, miễn phù hợp nghiệp vụ và chạy ổn định trong FINORA. Không quyết định nào dưới đây
được mô tả là yêu cầu pháp luật nếu không có dẫn chiếu trong `docs/LEGAL-COMPLIANCE.md`.

| # | Quyết định | Loại | Wave |
|---|---|---|---|
| D1 | CIC theo bản Node đã deploy; không phát triển lại CIC trong repo | Phạm vi | Đã áp dụng |
| D2 | Sửa lỗi hiện tại và làm luôn backlog theo wave; được sửa module của Hải để đồng bộ, đánh dấu “Hải review trước merge” | Ownership | 1–3 |
| D3 | Security (R8) tạm hoãn, chỉ sửa khi gắn trực tiếp với contract giữa service | Phạm vi | Sau wave 3 |
| D4 | Loan dùng adapter KYC thật của User; chỉ borrower `VERIFIED` được nộp hồ sơ; mock chỉ còn cho test/E2E | Nghiệp vụ | 2B (xong) |
| D5 | Hạn mức dư nợ của một borrower 100.000.000 VND, cấu hình được, nhãn `NEEDS_PRIMARY_SOURCE` (policy demo, chưa phải luật) | Policy demo | 2 (xong) |
| D6 | Nhà đầu tư tick xác nhận rủi ro (lưu version + thời điểm) trước lệnh sơ cấp (`LEGAL-DISCLOSURE-02`) | Nghiệp vụ | 2 (xong) |
| D7 | Ngày nghiệp vụ của Loan và Payment là `Asia/Ho_Chi_Minh` | Nghiệp vụ | 2 (xong) |
| D8 | Hợp đồng bị từ chối/hết hạn ký hoặc giải ngân thất bại → event bù trừ, Investment hủy cam kết và nhả hold | Contract | 1 (xong) |
| D9 | Phạt chậm trả giữ 0 cho hợp đồng hiện hành; chưa có write-off | Policy | Giữ nguyên |
| D10 | Trả trước một phần thu `scheduledDue + prepaidPrincipal`; quote cũ giữ snapshot | Policy | Giữ nguyên |
| D11 | E2E tự động: tài khoản mới mỗi lần, OTP đọc Redis User local, eKYC investor tắt chỉ trong stack E2E, ký MOCK, Auto-Invest tắt, admin demo, ghi dữ liệu test lên Neon/Fineract/Kafka dev | Kiểm thử | 1 (xong) |
| D12 | Commit theo wave trên nhánh `feat/finora-remaining-wave-<n>`, không push khi chưa được yêu cầu | Quy trình | 1–3 |
| D13 | R5 dùng PAdES-B-B với VNPT SmartCA UAT (ký hash, nhúng CMS bằng incremental update) | Kỹ thuật | 3 |
| D14 | R6 dựng Hyperledger Fabric local thật thay ledger MOCK | Kỹ thuật | 3 |

## 3b. Defect register

Nguồn: rà soát code ngày 2026-10-16/17 và evidence R1A (`docker/e2e/out/r1a-*.json`, không commit).
`Fixed` chỉ dùng khi có test hoặc evidence chạy thật đi kèm.

| ID | Mức | Workstream | Vấn đề và bằng chứng | Trạng thái |
|---|---|---|---|---|
| B01 | P0 | R2 | Payment đọc `total.amount` của repayment template; Fineract 1.15 trả `amount` cấp gốc nên mọi lệnh trả kỳ bị từ chối. Test xanh vì IT mock gateway | Fixed Wave 1 — unit test payload thật + E2E trả kỳ `COMPLETED` |
| B02 | P0 | R2 | Đối soát dò `reversed`, Fineract dùng `manuallyReversed` | Fixed Wave 1 — unit test |
| B03 | P0 | R9 | Gateway thiếu route `/api/v1/repayments/**`; admin repayment reconciliation bị route sang Loan; profile `docker` thiếu route Notification | Fixed Wave 1 — E2E trả nợ qua Gateway |
| B04 | P0 | R3 | `market_listings.purpose` VARCHAR(100) trong khi Loan gửi nhãn + 500 ký tự; listing vào DLT | Fixed Wave 1 — Investment V15 + IT |
| B05 | P0 | R2/R3 | Không có bù trừ khi đã đủ vốn mà hợp đồng bị từ chối/hết hạn hoặc giải ngân thất bại; tiền nhà đầu tư bị giữ vô thời hạn | Fixed Wave 1 — `LoanFundingCancelled` v1, Loan V27, IT Loan/Investment |
| B06 | P0 | G-POLICY | Loan/Payment dùng `Clock.systemUTC()`: từ 00:00–07:00 giờ VN, Fineract ghi giải ngân/trả nợ vào ngày hôm trước; hợp đồng ghi kỳ đầu 17/11 còn Fineract 16/11 (evidence R1A-BIZDATE) | Fixed Wave 2 — [`R2-W2`](R2-W2-business-date-and-financial-recovery.md); E2E 04:45 giờ VN: Fineract giải ngân và trả nợ 2026-10-17, kỳ đầu 2026-11-17 khớp hợp đồng |
| B07 | P0 | R2 | Booking dùng `expectedDisbursementDate` của lúc nộp hồ sơ, `submittedOnDate` = ngày booking. Fineract từ chối khi ngày booking muộn hơn (`error.msg.loan.submittal.cannot.be.after.expected.disbursement.date`, kiểm bằng `calculateLoanSchedule`) → luồng thật (gọi vốn tới 14 ngày, ký tới 7 ngày) sẽ `REPAIR_REQUIRED` sau khi tiền đã chuyển | Fixed Wave 2 — [`R2-W2`](R2-W2-business-date-and-financial-recovery.md); mọi ngày gửi Fineract là ngày giải ngân thực tế, unit test + E2E |
| B08 | P0 | R2 | Saga `CORE_BOOKING` không được nhận lại sau khi service chết giữa chừng | Fixed Wave 2 — lease + claim token, Loan V28; unit test. Restart JVM thật thuộc R1B |
| B09 | P0 | R2 | Payment disbursement `PROCESSING`/`RECONCILIATION_REQUIRED` không có worker phục hồi; truy vấn top-20 có thể bỏ đói | Fixed Wave 2 — lease → đối soát `lookup(sagaId)`, Payment V12–V13; IT PostgreSQL. Restart JVM thật thuộc R1B |
| B10 | P1 | R2 | Payment POST Fineract thành công nhưng GET sau đó lỗi HTTP bị coi là thất bại → hoàn tiền + `FAILED` | Fixed Wave 2 — 4xx/5xx/GET lỗi tách mã; unit test |
| B11 | P1 | R2 | Outbox Payment `send().get()` không timeout | Fixed Wave 2 — `outbox-publish-timeout`; `PaymentOutboxPublisherTest` |
| B12 | P1 | R2 | `DisbursementSaga.paymentFailed` ghi đè mọi trạng thái trừ `COMPLETED` (bù trừ Wave 1 đã chặn ở `WAITING_PAYMENT`) | Fixed Wave 2 — chỉ `WAITING_PAYMENT → PAYMENT_FAILED`, mâu thuẫn vào DLT; unit test |
| B13 | P1 | R2 | Lỗi Fineract khi đọc nghĩa vụ trả nợ trả `500 INTERNAL_ERROR` thay vì mã nghiệp vụ | Fixed Wave 2 — 422/503/502 qua `FineractQuoteErrors`; unit test |
| B14 | P1 | R3 | Investment `LoanDisbursed` yêu cầu listing `EFFECTIVE`, sai thứ tự event vào DLT và không tự replay | Fixed Wave 2 — [`R3-W2`](R3-W2-investment-event-order-and-portfolio.md); `LoanDisbursed` là bằng chứng Contract hiệu lực, IT |
| B15 | P1 | R3 | Admin `finalize`/`activate-notes` không có guard trạng thái; web hiện nút nguy hiểm | Fixed Wave 2 — `409 LISTING_NOT_DISBURSED`, web chỉ hiện nút sửa lỗi sau giải ngân; IT + web build |
| B16 | P1 | R3 | Portfolio chỉ đọc Note `ACTIVE`, khoản `DEFAULTED`/`CLOSED` biến mất khỏi danh mục | Fixed Wave 2 — vị thế `CLOSED/DEFAULTED`, mobile ẩn nút bán; IT |
| B17 | P1 | G-POLICY | Cấu hình phí tất toán hiện hành áp ngược cho hợp đồng cũ (chưa có policy version snapshot) | Fixed Wave 2B — [`G-POLICY-W2`](G-POLICY-W2-early-settlement-policy-version.md): hợp đồng ghi biểu phí (Loan V30), `LoanDisbursed` mang sang Payment (V14), danh mục bất biến; hợp đồng `LOAN_TERMS_V1` không thu phí (chờ owner xác nhận); E2E `r1a-20261017-060853` |
| B18 | P1 | Legal | Thiếu kiểm hạn mức dư nợ (`LEGAL-LIMIT-01`) và xác nhận rủi ro nhà đầu tư (`LEGAL-DISCLOSURE-02`) | Fixed Wave 2 — [`W2-LEGAL`](W2-LEGAL-borrower-exposure-and-investor-acknowledgement.md): hạn mức dư nợ nội bộ (policy demo, `NEEDS_PRIMARY_SOURCE`) + xác nhận lưu version/thời điểm; IT Loan/Investment |
| B19 | P1 | Loan/User | Loan chỉ có `BorrowerProfileProvider` mock (VERIFIED, 30 tuổi) | Fixed Wave 2B — [`W2-KYC`](W2-KYC-borrower-kyc-from-user.md): Loan chuyển tiếp token người vay tới User `/users/me/kyc-summary`, chặn người chưa `VERIFIED`, snapshot trên hồ sơ (V29); IT + kiểm thật `kyc-20261017-054750` |
| B20 | P1 | FE | Mobile tự tính số tiền trả kỳ; web gọi API duyệt listing không còn tồn tại và hiện thông điệp sai | Fixed Wave 2 — `GET /repayments/scheduled-due` (IT), mobile dùng số backend, web bỏ form duyệt |
| B21 | P1 | R9/R2 | DB Loan dùng chung chứa khoản vay trỏ tới Fineract local của máy khác; worker sync log lỗi 404 kèm stack trace ở mọi chu kỳ 10 giây, mapping Product↔Fineract không dùng chung được giữa máy | Partly fixed Wave 2 — log một dòng `warn`, không stack trace; tách DB theo môi trường vẫn thuộc R9 |
| B22 | P2 | R9 | DB dev dùng chung chậm 1–3 migration so với `main` (Loan V26, Investment V13–V14, Payment V11 chỉ được áp khi chạy E2E) | Open → R9 (CI/migration discipline) |
| B23 | P2 | R9 | Payment khai báo `spring-boot-starter-data-redis` nhưng không dùng Redis | Open → R9 |
| B24 | P2 | R7 | `BorrowerSignatureRequested` chưa có consumer; người vay không nhận thông báo đến lượt ký | Open → Wave 3 (R7) |
| B25 | P2 | DOC | `finora-loan/plans/` bị git-ignore nên link LN-009/010/015 trong tài liệu chung không mở được ở máy khác | Open — chờ owner quyết định có commit plan Loan hay không |
| B26 | P2 | User/Loan | Hai định danh người vay: Loan/Investment/Payment/AI dùng claim `user_id` (ID hồ sơ, ví dụ `borrowerId=553` trong log E2E `r1a-20261017-052157`), còn `CicMappingWorker` của User đăng ký mapping CIC theo `keycloakUserId` (test `guiKeycloakUserIdLamBorrowerIdVaCccdQuaKenhNoiBo`). `SecurityUtils.getCurrentUserId` còn rơi về `sub` khi token thiếu claim. Nếu bật mapping, event `LoanDelinquencyChanged.v1` (mang `borrowerId` của Loan) không bao giờ khớp mapping | Open — owner (Thái + Hải) chốt một định danh; khuyến nghị dùng `user_id` cho mọi contract và bỏ fallback `sub`. Hiện chưa gây lỗi chạy vì worker tắt (`USER_CIC_MAPPING_ENABLED=false`) |
| B27 | P2 | DOC/CIC | Tài liệu F06 và registry mô tả CIC Node có consumer `LoanDelinquencyChanged`, processed-event và hàng đợi chờ mapping. Kiểm ngày 2026-10-17: bản deploy (`/api/v1/suc-khoe`: kho tệp, 1000 hồ sơ) và source `cic-service-node` chỉ có API tra cứu điểm theo CCCD; `POST /api/v1/internal/borrower-mappings` không tồn tại trong source | Docs sửa ngày 2026-10-17 cho đúng hiện trạng. Theo D1 không phát triển CIC trong repo: lịch sử quá hạn của FINORA chưa được ghi sang CIC; owner quyết định có cần trong phạm vi demo |
| S01 | P1 | R8 | Tự đăng ký `ADMIN`; AI không xác thực (kể cả `PUT config`); endpoint nội bộ Notification `permitAll`; `mock-complete` không gắn môi trường; PIN token dùng lại được trong TTL; URL Neon mặc định trong `application.yml` của Loan | Deferred theo D3 |

## 4. R0 — Đồng bộ source, roadmap và tài liệu

### Việc làm

1. Commit/push độc lập ba repository backend, web và mobile; không đưa secret hoặc `.env` thật vào Git.
2. Web/mobile giữ các thay đổi servicing hiện hành: admin thẩm định cơ cấu, investor consent,
   quote trả trước/tất toán và nhãn input tiếng Việt rõ nghĩa.
3. Compose bỏ CIC Java cũ; `CIC_SERVICE_URL` mặc định là endpoint Node đã deploy.
4. Cập nhật `01-project-context.md`, roadmap và link plan này trong cùng commit backend.
5. Chạy validator rules, Docker Compose config, Java verify, web build và mobile TypeScript.

### Acceptance

- Ba repo sạch sau push và `HEAD` đã có trên `origin/main`.
- Không còn `../../cic-service`, `http://cic:9000` hoặc mô tả backend là skeleton trong cấu hình chính.
- Skill `finora-engineering` vẫn pass validator; không thay đổi rule nếu rule đang đúng.

R0 hoàn tất ngày 2026-10-17 tại các commit backend `c1ca1ba`, web `ac4ad2a` và mobile
`531f5ee`; commit tài liệu xác nhận cuối có thể nằm sau SHA backend nêu trên.

## 5. R1 — Bộ E2E vertical slice lặp lại được

### Hai checkpoint

- **R1A — Baseline/discovery:** dựng môi trường và dữ liệu xác định, chạy happy path một lần, ghi lại
  mọi sai lệch bằng trace/saga/event/business reference. Fault case được phép đỏ nhưng phải tái hiện được
  và được chuyển thành defect thuộc R2 hoặc R3.
- **R1B — Regression/acceptance:** sau khi R2/R3 hoàn tất, chạy lại cùng dữ liệu và tất cả fault case.
  Chỉ R1B xanh mới được gọi core flow là ổn định.

### Trạng thái R1A ngày 2026-10-17

Bộ chạy tự động nằm ở `docker/e2e/` (task plan
[`R1A-e2e-vertical-slice-baseline.md`](R1A-e2e-vertical-slice-baseline.md), runbook
[`E2E-VERTICAL-SLICE.md`](../../docs/testing/E2E-VERTICAL-SLICE.md)). Trên stack local với Fineract thật,
AI v17, CIC Node đã deploy, ký `MOCK`, nạp/giải ngân provider `mock` và proof `MOCK`, kịch bản đi hết từ đăng ký
tới phân bổ một kỳ trả nợ; kết quả từng lần chạy và defect phát hiện được ghi trong task plan R1A và defect
register mục 3b. R1A chưa thay R1B: restart giữa saga, timeout Fineract, sáu fixture servicing và test clock
vẫn chờ R2/R3.

### Luồng demo chuẩn

```text
Borrower đăng nhập/eKYC
 → tạo hồ sơ → AI chấm → admin duyệt
 → borrower chấp nhận điều khoản → listing mở
 → investor nạp mock/ZaloPay sandbox → đặt lệnh/hold
 → đủ vốn → lender ký → borrower ký
 → saga giải ngân → borrower nhận tiền → Note active
 → borrower trả một kỳ → investor nhận phân bổ
 → Loan/Investment/Blockchain/Notification cập nhật projection
```

### Dữ liệu và bằng chứng bắt buộc

| Bước | Evidence dễ hiểu | Evidence kỹ thuật |
|---|---|---|
| Hold | Số dư khả dụng giảm, số tiền đang giữ tăng | wallet/hold/ledger cùng reference |
| Ký | Cả hai bên thấy cùng hợp đồng và trạng thái | document hash + signer evidence |
| Giải ngân | Borrower tăng số dư, investor bị capture | balanced ledger + saga step + provider ref |
| Note | Investor thấy khoản đầu tư đang hiệu lực | Note/ownership snapshot/version |
| Trả kỳ | Borrower giảm tiền, investor nhận phần của mình | Fineract transaction + distribution ledger/outbox |

### Bộ servicing bắt buộc sau happy path

Mỗi cơ chế dùng một khoản vay/fixture độc lập để kết quả không phụ thuộc thứ tự chạy:

1. Trả đúng một kỳ.
2. Trả thiếu rồi khắc phục nghĩa vụ quá hạn.
3. Trả trước một phần gốc, không thu lãi tương lai và áp đúng policy fee/version.
4. Tất toán toàn bộ, đóng Loan/Note và phân bổ tiền đúng ownership snapshot.
5. Cơ cấu kỳ hạn: admin thẩm định, toàn bộ chủ Note biểu quyết, chỉ khi đồng thuận mới đổi lịch Fineract.
6. Default/CIC/cure: chuyển nhóm theo DPD liên tục, không cộng dồn các episode đã chữa; trả đủ quá hạn
   tạo episode phục hồi nhưng không xóa lịch sử.

### Fixture thời gian và reset

- Có lệnh seed/reset dữ liệu test theo namespace/run ID; không sửa tay database và không dùng PII thật.
- Test quá hạn 10/91/181/361 ngày dùng test clock hoặc fixture Fineract riêng chỉ bật trong profile test;
  tuyệt đối không cho API “đổi ngày” xuất hiện ở demo/prod.
- Mỗi fixture ghi rõ Product/servicing policy version, borrower, investor, số dư đầu, ngày nghiệp vụ và
  expected schedule/ledger/Note/CIC result.
- CIC Node là dependency ngoài FINORA: baseline phải kiểm tra contract/version, timeout và lỗi không
  sẵn sàng; không phát triển lại CIC trong repository này.

### Ca lỗi tối thiểu

- Gửi lặp cùng idempotency key.
- Kafka delivery trùng hoặc đến trễ.
- Dừng service giữa saga rồi bật lại.
- Fineract timeout sau khi có thể đã xử lý.
- Investment/Notification/Blockchain tạm tắt rồi bật lại.

### Acceptance

- R1A có script/checklist tạo dữ liệu sạch, kết quả mong đợi theo từng SoR và defect list gán đúng R2/R3.
- R1B chạy lại không cần sửa DB tay; core happy path và sáu cơ chế servicing đều xanh.
- Duplicate/restart/event trễ không tăng/giảm tiền lần hai; có trace/saga/event ID để lần theo toàn luồng.
- Báo cáo tách rõ dependency nào thật, sandbox, mock hoặc fail-closed.

## 6. G-POLICY — Phiên bản chính sách và điều khoản hợp đồng

### Cách hiểu nghiệp vụ

Khoản vay đã ký phải giữ nguyên cách tính đã cam kết. Khi FINORA đổi phí hoặc công thức, chỉ hợp đồng
mới sau ngày hiệu lực dùng phiên bản mới; không lấy cấu hình hiện tại áp ngược cho khoản vay cũ.

### Việc phải chốt trong task con

- Một policy version bất biến gồm ngày hiệu lực, dải kỳ hạn, rate/minimum fee, cách chia 80/20,
  quy tắc không thu lãi tương lai và lựa chọn rút ngắn kỳ/giảm tiền kỳ.
- Product/Contract lưu hoặc tham chiếu snapshot policy đã công bố; PDF/text/hash và borrower consent
  dùng cùng version. Artifact đã ký không được render lại bằng policy mới.
- Quote trả trước/tất toán trả về policy version, thời điểm tính, principal, accrued interest, từng phí,
  phần investor/FINORA và expiry; web/mobile chỉ hiển thị breakdown backend trả về.
- Migration/rollout nêu rõ khoản vay legacy dùng công thức nào; không “nâng cấp” hợp đồng đang hiệu lực
  bằng update hàng loạt.
- Dẫn `LEGAL-DISCLOSURE-01`, `LEGAL-CONTRACT-01`, `LEGAL-SERVICING-01`; tách điều pháp luật yêu cầu,
  policy demo FINORA và giới hạn kỹ thuật.

### Acceptance

- Test cùng ngày giao dịch trên hai hợp đồng có policy version khác nhau cho đúng hai kết quả đã cam kết.
- PDF, quote, Payment/Fineract breakdown, mobile và event evidence cùng version/công thức.
- Thay cấu hình hiện tại không làm đổi quote hợp lệ hoặc nghĩa vụ của hợp đồng cũ.

## 7. R2 — Saga, Fineract và financial recovery

### Mục tiêu nghiệp vụ

Nếu mạng lỗi sau khi tiền/core đã xử lý, hệ thống phải hỏi lại nguồn chuẩn và tiếp tục đúng bước,
không POST mù, không chuyển `FAILED` khi kết quả thực tế còn chưa biết.

### Việc còn lại

- Test restart tại từng bước disbursement và repayment với Fineract thật.
- Phân biệt `FAILED` với `UNKNOWN_OUTCOME`; unknown phải reconcile bằng external reference.
- Chứng minh capture, borrower credit, core booking và event publish không bị lặp.
- Hoàn thiện màn/admin action cho incident thật sự cần con người quyết định.
- Chốt auto-debit chỉ sau khi manual repayment/reconciliation đạt phase gate.

### Trạng thái Wave 2 ngày 2026-10-17

Task plan [`R2-W2`](R2-W2-business-date-and-financial-recovery.md) (`READY_FOR_REVIEW`): ngày nghiệp vụ Việt Nam,
booking theo ngày giải ngân thực tế, lease/claim token cho saga `CORE_BOOKING`, lease → đối soát cho khoản giải ngân
Payment, phân loại lỗi repayment/quote và timeout outbox. Còn lại của R2: restart/timeout trên stack thật (R1B), màn
admin cho incident cần người quyết định, auto-debit sau phase gate.

### Transaction/concurrency

```text
Transaction A: khóa aggregate + lưu durable command/outbox
Ngoài transaction: gọi Payment/Fineract/provider
Transaction B: đối chiếu external reference + ghi kết quả + outbox tiếp theo
```

Mỗi request có business idempotency key và external reference ổn định. Không giữ DB lock trong lúc
gọi HTTP. Worker dùng processing lease; hết lease phải reconcile trước khi tiếp quản.

### Acceptance

- Testcontainers/integration và smoke Fineract thật cho duplicate, timeout, restart đều xanh.
- Ledger cân bằng và principal/schedule không lệch sau mọi ca lỗi.
- `LEGAL-PAYMENT-01` và `LEGAL-SERVICING-01` được dẫn trong task triển khai.

## 8. R3 — Investment reconciliation và projection rebuild

### Việc còn lại

1. Reconcile `InvestmentOrder` ↔ Payment hold/capture/release bằng reference, không sửa Payment DB.
2. Repair Note activation khi `LoanDisbursed` đến trễ/trùng hoặc consumer restart.
3. Khóa ownership snapshot/version tại thời điểm phân bổ; test transfer cạnh repayment.
4. Rebuild portfolio/cashflow từ event/Note state thay vì sửa số hiển thị bằng tay.
5. Hoàn tất consumer thật của `RepaymentDistributed.v1` và servicing events cho cơ cấu/default/cure.

Trạng thái Wave 2 ngày 2026-10-17: task plan [`R3-W2`](R3-W2-investment-event-order-and-portfolio.md)
(`READY_FOR_REVIEW`, Hải review trước merge) đã làm mục 2 cho trường hợp event lệch thứ tự, guard thao tác sửa lỗi
và giữ vị thế đã kết thúc trong danh mục. Mục 1, 3, 4, 5 còn nguyên.

### Query/index cần chứng minh trong task con

- Queue theo `status + next_retry_at + id`, batch hữu hạn.
- Lookup unique theo `source_event_id`, `payment_hold_reference`, `loan_id/note_id/version`.
- Portfolio phân trang; không REST call theo từng Note và không N+1.

### Acceptance

- Tắt Investment trong lúc giải ngân/trả nợ rồi bật lại vẫn hội tụ đúng.
- Rebuild cho ra tổng principal/interest/cashflow bằng ledger/event nguồn.
- Một event trùng không tạo Note, ownership hoặc portfolio entry thứ hai.

## 9. R4 — Tiền ngoài FINORA

### Current state

- Có mock top-up và ZaloPay sandbox create/callback HMAC.
- Có wallet/ledger/hold/capture/transfer nội bộ.
- ZaloPay disbursement hiện fail-closed; withdrawal/link bank/VietQR chưa có.

### Việc còn lại

- Chỉ bắt đầu sau G-POLICY, R1B–R3 và security gate R8A.
- Chọn rõ phạm vi demo: ZaloPay sandbox top-up + mock payout, hoặc provider payout sandbox thật.
  Phương án khuyến nghị đã chọn ngày 2026-10-17: ZaloPay sandbox top-up + mock payout có callback HMAC và
  đối soát; payout thật chỉ mở sau legal review `LEGAL-PAYMENT-01`.
- Thêm withdrawal/payout request, provider reference, callback/query status và reconciliation.
- Không cộng/trừ ví chỉ dựa trên redirect mobile; callback phải xác thực chữ ký và idempotent.
- Batch/admin reconciliation: provider amount/status ↔ Payment transaction/ledger.
- Công bố rõ tiền sandbox là giả và không mô tả FINORA đang giữ tiền thật.

### Acceptance

- Callback trùng không cộng tiền hai lần; callback giả/sai HMAC bị từ chối.
- Timeout query lại provider bằng reference; kết quả unknown không tự retry chuyển tiền mù.
- Tổng provider settlement khớp ledger; mismatch vào queue đối soát.
- Tuân `LEGAL-PAYMENT-01` và phải legal review trước mọi môi trường tiền thật.

## 10. R5 — Chữ ký PDF nhiều bên

### Current state

SmartCA UAT tạo giao dịch ký hash, poll `signature_value` và lưu receipt/evidence. PDF signable giữ
nguyên bytes; trạng thái “đã ký” hiện được chứng minh bằng hash + receipt, chưa phải PDF PAdES nhúng.

### Hai hướng phải chốt

1. **Demo receipt rời:** giữ nguyên, UI/PDF cover công bố rõ bằng chứng tách rời.
2. **PAdES:** dùng SDK/contract chính thức để nhúng chữ ký theo incremental update; lender ký trước,
   mỗi lender tiếp theo ký bản mới, borrower ký cuối và hash/version phải theo chuỗi tài liệu.

Quyết định 2026-10-17 (D13): chọn hướng 2 ở mức PAdES-B-B với VNPT SmartCA UAT — PDFBox chuẩn bị chữ ký
external theo incremental update, SmartCA ký hash (`sign_type: hash`), BouncyCastle đóng gói CMS. Trước khi
code phải xác nhận `get_certificate` trả chứng thư DER đầy đủ; chứng thư UAT không phải chữ ký số có giá trị
pháp lý production.

### Acceptance nếu chọn PAdES

- PDF mở bằng trình đọc chuẩn thấy chữ ký/chứng thư và kiểm tra được integrity.
- Nhiều chữ ký không làm hỏng chữ ký trước.
- Retry cùng signer/document version không tạo hai chữ ký.
- Contract state chỉ active khi signer bắt buộc hoàn tất; `LEGAL-CONTRACT-01` được review.

## 11. R6 — Blockchain proof và Fabric

### Current state

Đã có proof store hash-only, idempotency, lease/retry/DLT, mock receipt và repayment consumer.
Fabric adapter thật cố ý fail-closed; contract/disbursement consumer và query/explorer chưa có.

### Mốc đề xuất neo hash

| Mốc | Payload tối thiểu | Không đưa lên chain |
|---|---|---|
| Hợp đồng ký đủ | document hash, contract/version, signer evidence hash | PDF, CCCD, tên/địa chỉ |
| Giải ngân | loan/saga/provider reference hash, amount hash | tài khoản ngân hàng |
| Repayment | source event, core transaction, distribution hash | chi tiết PII |
| Note | issuance/transfer/version hash | hồ sơ người vay |
| Servicing | settlement/reschedule/default decision hash | lý do nhạy cảm dạng thô |

### Việc còn lại

- Quyết định 2026-10-17 (D14): dựng Hyperledger Fabric local thật; kiểm RAM máy dev trước khi bật cùng
  stack E2E, ledger `MOCK` vẫn là mặc định khi Fabric tắt.
- Duyệt event contract từng mốc; thêm consumer idempotent.
- Dựng Fabric network/chaincode/identity, submit/query adapter và receipt thật.
- Explorer/query theo business reference và integrity reconciliation DB ↔ chain.
- Fabric outage chỉ retry/DLT proof, không rollback tiền/hợp đồng đã commit.

### Acceptance

- Hash đối chiếu lại được từ artifact nguồn; mock và Fabric receipt không bị nhầm.
- Duplicate/restart không tạo proof logic thứ hai.
- `LEGAL-DATA-01` và `LEGAL-CONTRACT-01` được kiểm tra trước rollout.

## 12. R7 — Notification hoàn chỉnh

### Current state

In-app delivery, unread/read và một số template servicing đã có. Chưa có vòng đời device token,
preference, provider push/SMS/email đầy đủ và bounded retry/DLT cho mọi sự kiện.

### Việc còn lại

- Ma trận actor × event: funding, ký, giải ngân, kỳ đến hạn, paid, overdue transition,
  reschedule request/decision, settlement, default/cure và reconciliation cần can thiệp.
- Chỉ gửi quá hạn khi chuyển stage/episode, không spam mỗi ngày.
- Push provider adapter, token lifecycle, opt-in/preference và redaction nội dung màn khóa.
- Dedup theo source event + recipient + channel; delivery lỗi không rollback nghiệp vụ.

## 13. R8 — Security, privacy và audit

### R8A — Gate tối thiểu trước provider bên ngoài

- Negative authorization test cho borrower/investor/admin/service account trên resource thật.
- Callback/webhook bắt buộc xác thực chữ ký, chống replay, idempotent và không tin redirect từ client.
- Secret scan, tách credential dev/demo, redaction token/CCCD/document/signature/provider payload.
- Rate limit cho login, eKYC, top-up, quote và mutation tiền/chữ ký.

R8A phải đạt trước R4 và trước khi bật SmartCA/provider ngoài môi trường local. Không cần chờ toàn bộ
hardening production mới chạy R5–R7 bằng mock/fail-closed.

### R8B — Hardening trước release

- Chốt JWT claim/role/resource authorization xuyên Gateway và service; test borrower/investor/admin.
- Admin RBAC chi tiết cho duyệt hồ sơ, servicing, reconcile và replay.
- Không log token, secret, CCCD, document URL, signature value hoặc provider payload nhạy cảm.
- Rotate secret, tách dev/demo/prod; quét repository và image trước release.
- CORS, rate limit, brute-force protection và audit append-only cho hành động nhạy cảm.
- Review retention/access/export/delete theo `LEGAL-DATA-01`.

Acceptance: negative authorization tests, secret scan và PII/log review có evidence; người dùng không
đọc/sửa tài nguyên của người khác chỉ bằng cách đổi ID.

## 14. R9 — Observability, vận hành và deployment

- Một lệnh dựng dependency cần thiết; health/readiness không chỉ kiểm tra mở cổng.
- Trace ID đi qua HTTP, outbox/Kafka, saga và provider; dashboard cho DLT, outbox lag,
  saga stuck, reconciliation incident và wallet invariant.
- Runbook cho restart, replay, manual reconcile và rollback migration tương thích.
- Backup/restore test cho từng database; không dùng chung credential giữa service.
- CI chạy validator rules, Java verify, web build, mobile typecheck và contract tests.
- Có environment matrix local/test/demo: dependency nào Docker, IDE, external URL, sandbox hoặc mock;
  env bắt buộc/tuỳ chọn và health check nghiệp vụ tương ứng.
- Pin/kiểm tra compatibility PostgreSQL 17 với Flyway/driver và nâng version có migration test, không bỏ
  qua cảnh báo compatibility chỉ vì ứng dụng vẫn khởi động.
- Monitor CIC Node như dependency ngoài: timeout/error-rate/contract smoke; outage không được tạo điểm
  mặc định hoặc đánh dấu hồ sơ đạt.

## 15. R10/R11 — Phần mở rộng sau phase gate

### AI

- Backtest/champion-challenger và model drift có dataset/version/metric.
- Fraud mở rộng bằng payment behavior contract; document forgery chỉ khi có dữ liệu hợp pháp.
- Early warning là tín hiệu hỗ trợ, không tự động xử phạt người vay không có policy duyệt.

### Investment

- Auto-invest: concurrency, on-behalf authorization, hold/release và giới hạn rủi ro.
- Secondary market: settlement/ownership atomicity, self-trade prevention, cancel race,
  SSE reconnect và reconciliation Payment ↔ Note transfer.
- Đây không phải blocker cho demo primary-market repayment.

## 16. Bản đồ dữ liệu hiện hành ở mức service

Plan này không thêm bảng. Quan hệ logic cần giữ khi viết task con:

```text
User/Keycloak identity
        │ borrowerId/investorId (logical ID, không FK chéo)
        ▼
LoanApplication 1 ── 0..1 LoanContract ── 1..N ContractSignerEvidence
        │ loanId/event                              │ document hash
        ▼                                           ▼
InvestmentListing 1 ── 1..N Commitment ── 1..N Note/OwnershipVersion
        │ hold/payment reference                    │ ownership snapshot
        ▼                                           ▼
PaymentTransaction 1 ── 2..N LedgerEntry      RepaymentDistribution
        │ core/provider reference                   │ event reference
        ▼                                           ▼
Fineract loan/transaction                    BlockchainProof/NotificationDelivery
```

Các mũi tên chéo service là quan hệ bằng external/business ID và event, không phải foreign key và
không cho phép join database chéo.

## 17. Test matrix tổng hợp

| Nhóm | Happy | Validation | Duplicate | Concurrency | Timeout/restart | Reconcile |
|---|---:|---:|---:|---:|---:|---:|
| Application/scoring | Có | Có | Có | — | AI timeout | Có |
| Funding/hold | Có | Có | Có | Bắt buộc | Payment timeout | Bắt buộc |
| Contract/signing | Có | Có | Có | signer song song | SmartCA poll/restart | Bắt buộc |
| Disbursement | Có | Có | Bắt buộc | Bắt buộc | Bắt buộc | Bắt buộc |
| Repayment/prepayment | Có | Có | Bắt buộc | Bắt buộc | Bắt buộc | Bắt buộc |
| Reschedule/default | Có | Có | Có | consent race | event trễ | Bắt buộc |
| Blockchain/notification | Có | Có | Bắt buộc | worker lease | provider outage | Bắt buộc |

Mỗi ô “Có/Bắt buộc” phải trỏ tới test ID/evidence cụ thể. Repayment/prepayment bao gồm đủ sáu fixture
ở R1; overdue/default dùng test clock thay vì thay ngày trực tiếp trong database.

## 18. Definition of Done cuối hệ thống

- G-POLICY, R1B–R3 đạt trước khi gọi luồng core là ổn định.
- Không có chỉnh DB tay trong kịch bản demo chuẩn.
- Mọi thay đổi tiền cân bằng và truy được bằng business/external reference.
- Fineract, Payment, Investment và Loan hội tụ cùng một trạng thái sau restart/event trễ.
- Web/mobile dùng API thật, có loading/empty/error/success và không hiển thị terminal giả.
- Tài liệu, event catalog, env example và UI test guide khớp code cùng commit.
- Các hạng mục production chưa làm được ghi rõ là mock/sandbox/backlog, không mô tả quá khả năng.

## 19. Nghiệm thu plan

Plan tổng hợp đang `ACTIVE`; đây là thứ tự điều phối, không thay thế task plan chi tiết. Mỗi workstream
mới vẫn bắt đầu bằng task plan `DRAFT`, chỉ chuyển `APPROVED` khi owner chốt phạm vi/contract. Khi hoàn
tất, ghi command/test evidence, commit SHA và bản đồ code thực tế thay vì chỉ đổi trạng thái bằng mô tả.

Trạng thái trong `finora-web/PLAN.md` và `finora-mobile/PLAN.md` là trạng thái nghiệm thu task, không phải
kết luận rằng toàn bộ code của luồng chưa tồn tại. `READY_FOR_REVIEW` nghĩa là code/build đã có nhưng vẫn
chờ owner/UAT; `IN_PROGRESS` hoặc `DRAFT` phải ghi rõ phần hiện hành và phần hardening còn dự kiến.
