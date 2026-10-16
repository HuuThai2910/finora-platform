---
task_id: R1A
title: Baseline E2E vertical slice tự động từ đăng ký tới một kỳ trả nợ
owner: Both
status: READY_FOR_REVIEW
depends_on: [R0]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
---

# R1A — Baseline E2E vertical slice

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

Trước R1A, mỗi service có test riêng nhưng chưa có cách lặp lại toàn bộ hành trình tiền thật trong hệ
thống: người vay nộp hồ sơ, nhà đầu tư góp vốn, hai bên ký, tiền giải ngân, người vay trả một kỳ và nhà
đầu tư nhận phần của mình. R1A dựng bộ chạy tự động bằng API thật, ghi bằng chứng tại đúng nguồn dữ liệu
chuẩn (SoR) và biến mọi sai lệch thành defect gán cho R2/R3/G-POLICY/FE. R1A không đòi mọi ca lỗi xanh;
R1B mới là cổng nghiệm thu sau khi sửa.

### Ai tham gia và nhìn thấy gì?

| Vai trò | Ai | Thấy gì trong lần chạy |
|---|---|---|
| Người vay | Tài khoản đăng ký mới mỗi lần (`e2e.borrower.<run>@finora.test`) | Hồ sơ, hợp đồng, ví nhận giải ngân, lệnh trả nợ |
| Nhà đầu tư | Tài khoản đăng ký mới mỗi lần (`e2e.investor.<run>@finora.test`) | Ví nạp mock, lệnh góp vốn, hợp đồng, Note, tiền nhận |
| Quản trị | Tài khoản demo đã có (`admin@finora.vn`), không tự đăng ký ADMIN | Duyệt hồ sơ PENDING_REVIEW, quản lý Product E2E |
| Người vận hành | Thái/Hải chạy script | Console theo bước và file evidence JSON |

### Luồng chính và luồng không thành công

```text
Đăng ký (OTP đọc từ Redis User local) → tạo PIN
→ Product E2E lãi cố định 12% (đồng bộ Fineract, kích hoạt)
→ borrower nộp 10.000.000 VND / 6 tháng → AI v17 chấm → admin duyệt nếu PENDING_REVIEW
→ điều khoản AUTO_AUTHORIZED hoặc borrower chấp nhận → LoanFundingRequested → listing OPEN
→ investor nạp mock 10.000.000 → đặt lệnh (PIN INVEST) → FULLY_FUNDED → Contract nhiều bên
→ investor ký (PIN SIGN_CONTRACT) → borrower ký → EFFECTIVE → saga → Payment capture + credit borrower
→ Fineract booking → LoanDisbursed → Note ACTIVE
→ borrower trả đúng nghĩa vụ Fineract qua Gateway (PIN REPAYMENT) → Fineract repayment
→ RepaymentDistributed → ví investor, portfolio, Loan projection, Notification, Blockchain proof (MOCK)
```

Bất kỳ bước nào không đạt trong thời hạn đều dừng lần chạy với trạng thái cuối, nhưng evidence vẫn được
lưu. Sai lệch không chặn happy path được ghi `[DEFECT]` và lần chạy vẫn đi tiếp.

### Dữ liệu nào ảnh hưởng kết quả?

- Product lãi cố định (`min = base = max = 12%`) để điều khoản cuối không đổi theo hạng AI; mã gắn với máy
  (`E2E_FIX12_<6 hex>`) vì mapping Product↔Fineract nằm ở DB Loan dùng chung còn Fineract là container
  riêng từng máy.
- Thu nhập 30.000.000/tháng, nợ 3.000.000/tháng, MORTGAGE, đại học, 48 tháng làm việc, mục đích `EDUCATION`
  không kèm mô tả tự do. Đây là dữ liệu tổng hợp, không phải PII.
- Người vay chưa eKYC nên AI không có CCCD để gọi CIC; quy tắc CIC bị đánh dấu thiếu dữ liệu và hồ sơ
  thường vào `PENDING_REVIEW` (cần admin) — đúng hành vi fail-safe, không tạo điểm CIC giả.

### Điều kiện hoàn thành nghiệp vụ

- Script tạo dữ liệu sạch theo run ID và chạy lại không cần sửa DB tay.
- Mỗi bước có kết quả mong đợi theo SoR (mục 6) và evidence JSON.
- Defect list gán đúng workstream; mock/sandbox được ghi rõ trong evidence.

## 1. Current state, phạm vi và ngoài phạm vi

Trong phạm vi: happy path một kỳ, idempotent replay HTTP ở mọi lệnh tiền/ký, drill Kafka giao trùng
`LoanDisbursed`/`RepaymentDistributed`. Ngoài phạm vi R1A: restart giữa saga, Fineract timeout, sáu fixture
servicing và test clock (thuộc R1B sau R2/R3); SmartCA thật, PAdES, Fabric, payout thật (Wave 3).

## 2. Ownership, SoR và dependency

| Bước | SoR | Cách đọc trong script | Thật / mock |
|---|---|---|---|
| Tài khoản, PIN | User + Keycloak | REST qua Gateway; OTP đọc Redis User local | Thật (OTP không qua email) |
| Hồ sơ, điều khoản, Contract, saga | Loan | REST Loan `8081` | Thật; chữ ký `MOCK + CLICK_WRAP_MVP` |
| Chấm điểm | AI v17 + CIC Node deploy | Kết quả qua Loan | Thật; CIC thiếu dữ liệu vì chưa eKYC |
| Listing, order, Note | Investment | REST Investment `8084` | Thật; Auto-Invest tắt, eKYC investor tắt chỉ trong stack E2E |
| Ví, hold, capture, repayment | Payment ledger | REST Payment `8082`, trả nợ qua Gateway | Top-up/disbursement provider `mock` |
| Lịch trả, giao dịch core | Fineract 1.15 local | REST Fineract read-only | Thật |
| Event | Kafka | Console consumer group tạm | Thật |
| Proof | Blockchain | SQL read-only container local | Provider `MOCK`, không phải Fabric |
| Thông báo | Notification | REST Notification `8086` | In-app thật, không push |

Borrower KYC trong Loan vẫn là profile mock VERIFIED của profile `local` (adapter KYC thật thuộc Wave 2).

## 3. Entity/value object/snapshot/projection

R1A không thêm entity hay migration. Dữ liệu do script tạo là dữ liệu nghiệp vụ bình thường của từng
service (tài khoản, Product, hồ sơ, listing, order, Contract, saga, ledger, Note, repayment, notification,
proof). Không có bảng “test” riêng và không xóa dữ liệu sau khi chạy vì ledger/history là bất biến.

## 4. ERD và lý do cardinality

Không đổi ERD. Quan hệ được kiểm trong một lần chạy: `LoanApplication 1 — 0..1 LoanContract`,
`MarketListing 1 — 1..N Commitment — 1..N Note`, `PaymentRepayment 1 — 1 Fineract transaction`
(`externalId = FINORA-REPAY-<repaymentId>`), `RepaymentDistributed 1 — 1 BlockchainProof`.

## 5. State machine và invariant được kiểm

- Application `SUBMITTED → … → APPROVED`; funding `REQUESTED → FULLY_FUNDED`.
- Listing `OPEN → FULLY_FUNDED`; order `COMMITTED`; commitment `ACTIVE → FINALIZED`; Note `ACTIVE`.
- Contract `PENDING_LENDER_SIGNATURES → PENDING_BORROWER_SIGNATURE → EFFECTIVE`; saga `COMPLETED`.
- Repayment `COLLECTED → CORE_POSTING → CORE_POSTED → COMPLETED`.
- Invariant tiền: top-up cộng đúng một lần; hold giảm available và tăng held đúng số lệnh; giải ngân cộng
  borrower đúng số vay và capture hết held; trả nợ trừ borrower đúng nghĩa vụ Fineract;
  `principal + interest + fee + penalty = amount`; investor duy nhất nhận đủ `principal + interest`.

## 6. API/event/worker contract dùng trong script

Bảng bước → endpoint → kết quả mong đợi nằm trong runbook
[`docs/testing/E2E-VERTICAL-SLICE.md`](../../docs/testing/E2E-VERTICAL-SLICE.md). Mỗi lệnh có
`Idempotency-Key` được gửi lại cùng khóa để chứng minh không tạo tác dụng phụ lần hai; lệnh nộp hồ sơ còn
được gửi cùng khóa với nội dung khác để chứng minh `409 IDEMPOTENCY_KEY_REUSED`.

## 7. Query, pagination, N+1 và index

Script đọc listing bằng trang `status=ALL&size=100` vì Investment chưa có filter theo `loanId`; với dữ liệu
dev hiện tại là vài trang. Đây là giới hạn của công cụ kiểm thử, không phải yêu cầu index mới. Kafka đọc từ
đầu topic nên chỉ phù hợp broker local; R9 cần API đối soát khi dữ liệu lớn.

## 8. Transaction, concurrency và idempotency

Script không mở transaction hay sửa DB. Concurrency thật (hai investor cùng đặt phần còn lại, ký song song)
thuộc R1B/R3. Idempotency được kiểm bằng replay cùng khóa ở: nộp hồ sơ, duyệt, nạp ví, đặt lệnh, ký lender,
trả nợ; và bằng Kafka replay cùng `eventId` ở `LoanDisbursed`, `RepaymentDistributed`.

## 9. External failure, retry, reconciliation/compensation

Polling có hạn cuối từng bước (mặc định 300 giây). Trạng thái kết thúc sai (`REJECTED`, `PAYMENT_FAILED`,
`REPAIR_REQUIRED`, `FAILED`, `RECONCILIATION_REQUIRED`) dừng ngay. Nhánh bù trừ `LoanFundingCancelled` được
chứng minh bằng integration test Loan/Investment trong Wave 1; drill hết hạn ký trên stack thật cần chờ
7 ngày hoặc test clock nên để R1B.

## 10. Security, audit, logging và PII

- Không ghi token, PIN, PIN token, OTP, mật khẩu vào console/evidence; email tài khoản test là dữ liệu tổng hợp.
- Không tự đăng ký ADMIN dù API hiện cho phép (lỗ hổng đã ghi ở R8).
- Không dùng ảnh CCCD giả, không sửa DB KYC; investor eKYC chỉ tắt trong stack E2E bằng biến môi trường.
- Đọc chéo dữ liệu chỉ ở các ngoại lệ read-only nêu ở mục 2.

## 11. Migration, file và rollout

File: `docker/e2e/finora-e2e.psm1`, `docker/e2e/start-local-services.ps1`, `docker/e2e/run-vertical-slice.ps1`,
`docs/testing/E2E-VERTICAL-SLICE.md`, `.gitignore` (`docker/e2e/out/`). Không có migration riêng; khi chạy
code hiện hành, Flyway của từng service áp migration còn thiếu lên DB dùng chung (đã ghi trong evidence).

## 12. Test plan và acceptance criteria

- Chạy hai lần liên tiếp với run ID khác nhau đều đi hết luồng, không sửa DB tay.
- Mọi kiểm tra `[OK]`; sai lệch không chặn luồng nằm trong `defects` của evidence và trong defect register
  của plan tổng.
- Evidence ghi rõ dependency thật/mock/sandbox.

## 13. Nghiệm thu

Hai lần chạy liên tiếp sau khi chốt code ngày 2026-10-17, cùng stack và cùng cấu hình, đều `PASS`:

| Run ID | Kiểm tra | Thời gian | Hồ sơ / Contract | Fineract loan / transaction | Defect |
|---|---|---|---|---|---|
| `r1a-20261017-034428` | 42/42 | 222 giây | `LA-FB2A7869A0D94C41B1E4` / `LC-DECD281BF9D347C5A81E` | 11 / 28 | R1A-BIZDATE |
| `r1a-20261017-034811` | 42/42 | 238 giây | `LA-6415E07FEDF04E888420` / `LC-46ED50150FDE4A889C84` | 12 / 31 | R1A-BIZDATE |

Kết quả giống nhau ở cả hai lần: AI v17 trả hạng C, điểm 66,79 → `PENDING_REVIEW` → admin `POLICY_APPROVED`;
điều khoản `AUTO_AUTHORIZED` ở 12%; listing `OPEN` sau vài giây; lệnh 10.000.000 `COMMITTED`; hai bên ký cùng
PDF (SHA-256 khớp); saga `COMPLETED`; 10 Note `ACTIVE`; kỳ 1 thu 1.725.000 (gốc 1.625.000, lãi 100.000, phí 0)
và investor nhận đủ 1.725.000; thông báo `REPAYMENT_CREDITED` và proof `CONFIRMED` (MOCK). Mỗi topic trong
luồng có đúng một `eventId`; drill phát lại `LoanDisbursed`/`RepaymentDistributed` không tạo Note, tiền, thông báo
hay proof thứ hai. Evidence JSON nằm trong `docker/e2e/out/` (git-ignore). Thái chuyển `ACCEPTED`.

## 14. Bản đồ code thực tế

- `start-local-services.ps1`: kiểm container hạ tầng, so thời điểm build `finora-user` với commit cuối của
  User/Common, bật `blockchain-postgres` với mật khẩu local sinh trong `out/.env.e2e`, khởi động lần lượt
  sáu JVM với heap giới hạn và biến E2E (`SPRING_PROFILES_ACTIVE=local`, `FINORA_SIGNATURE_PROVIDER=MOCK`,
  `FINERACT_BOOKING_PROVIDER=fineract`, `INVESTMENT_AUTO_INVEST_ENABLED=false`, `INVESTMENT_REQUIRE_EKYC=false`,
  Blockchain listener/worker bật với provider `MOCK`).
- `run-vertical-slice.ps1`: các bước `Assert-Preflight → Initialize-E2EProduct → Submit-Application →
  Complete-Underwriting → Wait-Listing → Fund-Listing → Sign-Contract → Wait-Disbursement → Pay-Installment →
  Collect-KafkaEvidence → Invoke-DuplicateDrills`; `finally` ngừng Product E2E để không hiện trong danh mục.
- `finora-e2e.psm1`: HTTP UTF-8 không ném lỗi theo status, tiền `decimal` (từ chối `double`), Kafka đọc/phát lại,
  Redis OTP, SQL read-only, Fineract GET, evidence JSON.

## 15. Sai khác so với dự kiến và bằng chứng

Lỗi của công cụ, đã sửa trong harness (không phải lỗi service):

- Run `r1a-20261017-031707` dừng ở bước trả nợ `400 VALIDATION_FAILED`: Fineract trả `amount = 1725000.000000`
  (scale 6) và harness gửi nguyên scale đó trong khi API nhận tối đa 2 chữ số thập phân. Harness chuẩn hóa tiền
  gửi đi và từ chối giá trị lẻ hơn 2 chữ số thay vì làm tròn âm thầm. Client nào chuyển thẳng số Fineract cũng
  gặp lỗi này; quote trả nợ từ Payment thuộc B20.
- Run `r1a-20261017-032041` đạt 38/38 kiểm tra nghiệp vụ nhưng drill lỗi: Windows PowerShell 5.1 chèn BOM vào
  stdin khi pipe sang `docker exec`, console producer báo thiếu dấu phân cách header. Harness chuyển sang
  `docker cp` file UTF-8 không BOM; đồng thời sửa hàm đọc Kafka trả mảng lồng (đếm `records` sai, `eventId` đúng).

Môi trường được chỉnh trước khi chạy (không đổi code service):

- Container `finora-user` build ngày 2026-10-05 thiếu API PIN; đã build lại từ source hiện hành. Container
  `finora-ai` cũng tạo từ Compose cũ và gọi CIC Java `http://cic:9000`; đã tạo lại để dùng CIC Node đã deploy.
- `docker/postgresql/init-service-user.sh` trong working copy là CRLF (index Git là LF) nên container Blockchain
  không khởi tạo được; đã chuẩn hóa lại LF, nội dung trùng với Git.
- Flyway áp migration còn thiếu lên DB dev dùng chung khi service khởi động (Loan V26–V27, Investment V13–V15,
  Payment V11).

Defect hệ thống phát hiện qua R1A (chi tiết trong defect register của plan tồn đọng):

- B06 `R1A-BIZDATE`: chạy lúc 03:00–04:00 giờ VN, Fineract ghi giải ngân và trả nợ ngày 2026-10-16 (ngày UTC)
  trong khi hợp đồng ghi giải ngân dự kiến 2026-10-17, kỳ đầu 2026-11-17; Fineract tính kỳ đầu 2026-11-16 và
  projection Loan đổi sang lịch Fineract sau khi sync.
- B07: booking gửi `expectedDisbursementDate` của lúc nộp hồ sơ; `calculateLoanSchedule` xác nhận Fineract từ
  chối khi ngày booking muộn hơn. E2E không chạm lỗi này chỉ vì cả luồng diễn ra trong vài phút.
- B21–B23: DB Loan dùng chung trỏ tới Fineract của máy khác (log lỗi 404 mỗi chu kỳ sync), DB dev chậm migration,
  dependency Redis không dùng ở Payment.

Phạm vi drill: chỉ kiểm projection Investment, ví investor, thông báo và proof. Chống trùng của consumer
`LoanDisbursed` phía Payment (tài khoản thu nợ) chưa có API đọc nên chưa được kiểm trong R1A.
