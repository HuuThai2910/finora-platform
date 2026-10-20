---
document_type: TASK_PLAN
task_id: LN-013
owner: Thai
reviewers:
  - Hai
status: IN_PROGRESS
depends_on:
  - LN-011
  - LN-012
  - Payment repayment contract
updated_at: 2026-10-03
---

# LN-013 — Repayment và API đọc lịch trả

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

LN-013 định nghĩa đường đi của một lần trả nợ và cách borrower xem lịch trả. Payment thu tiền và giữ sổ cái; Fineract phân bổ số tiền vào gốc/lãi/phí/phạt; Loan chỉ hiển thị kết quả và trạng thái khoản vay. Tách trách nhiệm này tránh có hai nơi tự tính ra hai số khác nhau.

### Ai tham gia và nhìn thấy gì?

- Borrower xem kỳ đến hạn, số phải trả, khoản đã trả và độ mới dữ liệu; sau đó yêu cầu Payment thanh toán.
- Payment thu tiền đúng một lần và gọi Fineract bằng cùng business reference.
- Fineract trả transaction ID và breakdown chính thức.
- Investment dùng breakdown/ownership snapshot để phân bổ nhà đầu tư; Blockchain/Notification xử lý sau, không được rollback tiền.

### Luồng chính

```text
Xem lịch Fineract → borrower trả → Payment thu và ghi ledger
→ Payment post repayment vào Fineract → nhận breakdown
→ cập nhật Payment/Investment/Loan projection → thông báo/bằng chứng
```

### Dữ liệu quan trọng và ảnh hưởng

| Dữ liệu | Ý nghĩa nghiệp vụ | Ảnh hưởng |
|---|---|---|
| `paymentReference/externalId` | Mã duy nhất của lần trả | Xuyên provider, ledger và Fineract để retry không thu/ghi hai lần |
| amount/currency/date | Số tiền borrower thật sự trả | Payment xác nhận trước khi gọi core |
| `fineractTransactionId` | Giao dịch đã được core ghi nhận | Cơ sở reconcile/reversal, không post lại mù |
| principal/interest/fee/penalty | Fineract đã phân bổ tiền vào đâu | Hiển thị và phân bổ investor; Loan không tự tính |
| due/paid/outstanding/status | Tình trạng từng kỳ | UI kèm `source=FINERACT`, `dataAsOf`, `stale` |

### Trường hợp lỗi, ví dụ và điều kiện hoàn thành

Nếu Payment thu 4.513.000 VND thành công nhưng Fineract timeout, hệ thống đưa vào repair và tra cứu theo `PAY-...`; không thu borrower lần hai. Notification hoặc Blockchain lỗi không hoàn tác khoản trả. LN hoàn tất khi test provider-success/core-fail, timeout-after-success, duplicate reference và schedule ownership chứng minh không có đường code thu hoặc ghi repayment hai lần.

### Đối chiếu pháp lý

- Áp dụng `LEGAL-PAYMENT-01` và `LEGAL-DISCLOSURE-01` trong [Sổ đối chiếu pháp lý FINORA](../../docs/LEGAL-COMPLIANCE.md). Kênh thu nợ tuân theo [Điều 11 Nghị định 94/2025/NĐ-CP](https://vanban.chinhphu.vn/?classid=1&docid=213519&pageid=27160); Loan không tạo “ví nội bộ” để giữ tiền ngoài provider được phép.
- Borrower phải xem từng kỳ, số đã trả, còn phải trả và breakdown gốc/lãi/phí/phạt bằng nhãn dễ hiểu; không hiển thị raw Fineract JSON. Thông tin và chứng từ giao dịch từ xa đối chiếu [Luật Bảo vệ quyền lợi người tiêu dùng 19/2023/QH15](https://vanban.chinhphu.vn/?docid=208363&pageid=27160) và [Luật Giao dịch điện tử 20/2023/QH15](https://vanban.chinhphu.vn/?classid=1&docid=208421&pageid=27160&typegroupid=3).
- Payment success/core timeout là trạng thái repair cần đối soát, không được thu lại. Reversal/đính chính phải giữ business reference và audit trail bất biến.

## 1. Mục tiêu và ranh giới

Payment thu tiền và sở hữu FINORA ledger. Sau khi thu thành công, Payment ghi repayment vào Fineract. Fineract phân bổ gốc/lãi/phí/phạt và là nguồn chuẩn của balance/schedule. Loan chỉ cung cấp read model/API và nhận kết quả để cập nhật projection.

Không để Loan tự tính lại allocation, không để Payment sửa database Loan/Fineract trực tiếp và không thu tiền lần hai khi Blockchain/Notification lỗi.

## 2. Luồng trả nợ

1. Loan/Notification hiển thị hoặc nhắc kỳ dựa trên schedule từ Fineract với `dataAsOf`.
2. Borrower yêu cầu thanh toán qua Payment.
3. Payment thu tiền và ghi immutable ledger/provider reference idempotently.
4. Chỉ sau khi thu thành công, Payment gọi Fineract repayment API.
5. Fineract trả transaction ID và breakdown đã phân bổ.
6. Payment lưu `fineractTransactionId` cùng breakdown; Investment dùng ownership snapshot để phân bổ nhà đầu tư.
7. Fineract event hoặc reconcile cập nhật Loan projection.
8. Blockchain/Notification xử lý bất đồng bộ từ event đã xác nhận.

Nếu Payment đã thu nhưng Fineract chưa ghi nhận, tạo repair/reconciliation state; không gọi provider thu lại.

## 3. Fineract repayment contract draft

Endpoint mục tiêu:

`POST /loans/{loanId}/transactions?command=repayment`

```json
{
  "transactionDate": "2026-09-15",
  "transactionAmount": "4513000",
  "paymentTypeId": "${FINORA_REPAYMENT_PAYMENT_TYPE}",
  "externalId": "PAY-2026-000001",
  "note": "FINORA payment reference only",
  "dateFormat": "yyyy-MM-dd",
  "locale": "en"
}
```

Response allowlist gồm core transaction/resource ID, loan ID, principal/interest/fee/penalty breakdown và changes/status thật sự cần. Exact hỗ trợ `externalId`, reversal và payment type phải được khóa bằng Swagger/contract test Fineract 1.15.

## 4. Contract Loan–Payment

Loan cung cấp cho Payment tối thiểu:

- FINORA loan ID và Fineract loan ID/reference;
- currency và amount constraints;
- due context chỉ để hiển thị, không phải nguồn allocation;
- correlation/idempotency convention.

Payment trả/phát event allowlist:

- `paymentReference`, `fineractTransactionId`;
- amount/currency, transaction time;
- provider/core status;
- breakdown core đã xác nhận;
- error/repair state nếu ledger và core lệch nhau.

Exact DTO/event cần Hải review nếu Investment tiêu thụ breakdown hoặc Payment contract dùng vùng chung.

### 4.1 Quan hệ sở hữu dữ liệu dự kiến

```text
LoanServicingProjection ── read summary/schedule ──► UI Loan
          ▲
          │ event/reconcile
       Fineract ◄── post repayment ── Payment Ledger ◄── thu tiền ── Provider
          │                                  │
          └── official allocation            └── paymentReference owner
                  │
                  └── event allowlist ──► Investment distribution
```

- Payment transaction/ledger **không phải entity của Loan Service**. Payment sở hữu việc tiền đã thu hay chưa.
- Fineract transaction/allocation là nguồn chuẩn gốc/lãi/phí/phạt; Loan không tạo một bảng allocation thứ hai chỉ để tự tính lại.
- Loan chỉ cập nhật `LoanServicingProjection` và incident/reference tối thiểu từ event/reconcile. Đây là dữ liệu đọc, có thể stale và phải kèm `dataAsOf`.
- Nếu sau này Loan lưu repayment history để UI nhanh, mỗi row phải tham chiếu unique `paymentReference`/`fineractTransactionId`, là projection bất biến chứ không phải ledger có quyền sửa tiền.

## 5. API borrower

- `GET /api/v1/loans/{id}/repayment-schedule`
- `GET /api/v1/loans/{id}/repayments` nếu Loan chỉ proxy/projection tối thiểu đã chốt

Response schedule phải phân biệt:

- due date;
- principal, interest, fee, penalty;
- paid/outstanding;
- status kỳ;
- `dataAsOf`, `source=FINERACT`, `stale`.

Ownership được kiểm tra bằng actor abstraction. Endpoint danh sách không remote-call từng loan; dùng projection/batch strategy.

## 6. Idempotency và failure

- Payment business reference là idempotency key xuyên provider, Payment ledger và Fineract command.
- Timeout sau Fineract đã nhận: query/reconcile theo external reference trước khi post lại.
- Provider success/core failure: repair queue, không thu lại tiền.
- Core success/local save failure: reconcile core transaction, không post repayment mới.
- Reversal/refund là command riêng có policy/audit, không xóa transaction cũ.
- Lỗi Notification/Blockchain không rollback payment.

Không có một transaction chung cho Payment DB và Fineract. Payment cần chuỗi bền vững: transaction local ghi intent/ledger state → gọi provider/core ngoài transaction → transaction local ghi kết quả. Timeout là “chưa biết kết quả”, không đồng nghĩa “chưa thu/chưa post”; phải query/reconcile trước retry.

Hai worker hoặc hai lần borrower bấm trả đồng thời phải bị chặn bằng unique business reference, lock/version và state machine phía Payment. Loan consumer dùng processed-event/source version để không cập nhật projection hai lần; Loan không tự giải quyết bằng cách trừ balance.

## 7. Persistence phía Loan

Loan không sao chép Payment ledger. Chỉ lưu:

- projection balance/schedule metadata từ LN-012;
- last repayment/source event ID nếu cần idempotency;
- reconciliation incident/reference cần hiển thị;
- không lưu credential, raw provider payload hoặc breakdown trùng nếu không có consumer Loan.

## 8. Index, N+1 và test

- Index schedule/read projection theo `(borrower_id, status, id)` và unique core ID theo query thực tế. Không index raw JSON schedule nếu không có truy vấn lọc bên trong JSON.
- Query-count test cho danh sách khoản vay/lịch.
- Contract test repayment success, timeout-after-success, duplicate external ID và core 4xx/5xx.
- Test provider success/core fail và restart repair worker.
- Test ownership/PII/log masking.
- Test breakdown không bị Loan tự tính khác Fineract.

N+1 cần kiểm tra cả database lẫn network: màn “các khoản vay của tôi” không được lấy N loan rồi gọi Fineract N lần. Nó đọc projection bằng một query; endpoint detail mới lấy schedule của một loan hoặc snapshot đã được reconcile. Batch nhắc nợ dùng bulk/batch có rate limit, không chạy trong HTTP request của người dùng.

## 9. Nghiệm thu

- Payment và Fineract có một business reference xuyên suốt.
- Không có đường code thu tiền hoặc ghi repayment hai lần khi retry.
- Borrower thấy schedule có nguồn và độ mới rõ.
- Loan không trở thành ledger thứ hai.
- Contract được Thái/Hải review đúng ownership trước merge.

## 10. Hiện trạng triển khai ngày 2026-10-03

Đã có lát happy case backend:

1. Payment nhận `POST /api/v1/repayments` với `Idempotency-Key`, `loanApplicationId`, `amount`,
   `transactionDate`; xác minh borrower là chủ khoản vay và số tiền bằng đúng repayment template
   hiện hành do Fineract trả về. Số khác kỳ không được lách qua happy case.
2. Ledger thu tiền ghi borrower available debit và repayment clearing credit trong cùng transaction.
3. Worker gọi Fineract `POST /loans/{id}/transactions?command=repayment` bằng
   `externalId=FINORA-REPAY-{repaymentId}`, rồi đọc transaction breakdown và loan summary.
4. Payment chia gốc/lãi theo tỷ lệ dư nợ từng Note bằng thuật toán largest remainder đến 2 chữ số;
   credit theo investor hiện tại, phí/phạt vào platform, tổng debit/credit phải cân.
5. `NoteOwnershipChanged.v1` giữ read model chủ Note ở Payment; `RepaymentDistributed.v1` cập nhật
   Loan projection và Investment Note idempotently.

### Field persistence đã triển khai

| Bảng | Field nghiệp vụ |
|---|---|
| `payment_loan_accounts` | Application/Contract/listing/borrower/Fineract loan/currency/status/activated/version/audit |
| `payment_note_ownership` | Note ID/number, loan/listing, investor hiện tại, outstanding, currency, source reference/time/version |
| `payment_repayments` | repayment type/idempotency/quote, loan/borrower/Fineract IDs, total/core/platform fee, date/status, hai ledger transaction IDs, Fineract transaction ID, breakdown, remaining/next due, attempt/error/completion/version/audit |
| `payment_early_settlement_quotes` | quote UUID, owner/core IDs, Fineract breakdown, policy/rate/phí snapshot, tổng thu, TTL/status/version/audit |

### Trạng thái hiện hành

`COLLECTED → CORE_POSTING → CORE_POSTED → COMPLETED`; kết quả core không chắc chắn chuyển
`RECONCILIATION_REQUIRED` và không tự POST lại. Worker chỉ GET transaction theo
`externalId=FINORA-REPAY-{repaymentId}`; tìm thấy mới tiếp tục. Lệnh chắc chắn bị Fineract từ chối
được hoàn tiền bằng ledger đảo chiều idempotent rồi chuyển `FAILED`.

Chưa xong: E2E với Fineract local bằng một khoản vay thật, xử lý manual escalation nếu transaction
không bao giờ xuất hiện và API/UI mobile. Vì vậy LN-013 vẫn `IN_PROGRESS`.

Cập nhật 2026-10-17 (Wave 1): đối chiếu với Fineract 1.15 thật cho thấy repayment template trả nghĩa vụ
kỳ kế tiếp ở field `amount` cấp gốc, không có object `total`; giao dịch đảo dùng `manuallyReversed`, không
phải `reversed`. Hai lỗi này làm mọi lệnh trả kỳ bị từ chối dù test Payment xanh vì IT mock gateway.
`FineractRepaymentGateway` và `FineractRepaymentGatewayTest` đã sửa theo payload thật; Gateway có route
`/api/v1/repayments/**`. Bằng chứng chạy thật nằm trong evidence R1A (`docs/testing/E2E-VERTICAL-SLICE.md`).

### Bổ sung tất toán trước hạn ngày 2026-10-03

- Payment đọc báo giá qua `GET /loans/{id}/transactions/template?command=prepayLoan` theo ngày nghiệp
  vụ; đây là thao tác read-only, không tạo transaction trước khi borrower xác nhận. Khi xác nhận,
  Payment thu tiền rồi mới POST repayment bằng business reference bền vững; không tự tính
  gốc/lãi/phí/phạt core.
- Quote có TTL 15 phút, owner check, row lock, dùng một lần và liên kết unique sang repayment.
- Policy demo snapshot theo một nguồn Vietcombank: 0,5%/miễn phí cho khoản tối đa 12 tháng theo nửa
  thời hạn; 1% cho 13–24 tháng; tối thiểu 100.000 đồng khi rate khác 0.
- Phí FINORA tách khỏi `coreAmount`; ví thu `coreAmount + platformFee`, Fineract chỉ nhận `coreAmount`.
- Khi Fineract trả `totalOutstanding=0`, Payment đóng account, Loan chuyển `SETTLED`, Investment đóng Note.
- Flyway Payment V1–V7/Hibernate PostgreSQL 17 và unit test ba service đều đạt.
- Trả trước một phần vẫn fail-closed qua endpoint trả đúng kỳ; chưa mở cho tới khi khóa được contract
  preview lịch mới đúng theo cấu hình sản phẩm Fineract, tránh tự dựng lịch sai core.

### Gate trả trước một phần đã xác minh với Fineract 1.15

- Product và schedule FINORA hiện dùng `transactionProcessingStrategyCode=mifos-standard-strategy`;
  chưa gửi `paymentAllocation` hoặc `futureInstallmentAllocationRule`.
- OpenAPI local có `GET /loans/{loanId}/transactions/reamortization-preview`, nhưng endpoint này
  preview việc tái phân bổ từ giao dịch đã có và không nhận số tiền giả định người vay định trả.
- Vì chưa thể cho người vay xem trước một lịch đúng theo core, FINORA không được tính lịch riêng hoặc
  chấp nhận `PARTIAL_PREPAYMENT` như repayment bình thường.
- Việc mở cơ chế này cần Product/version mới với advanced payment allocation, API preview theo số tiền,
  snapshot consent và E2E chứng minh lịch sau post khớp lịch đã preview.
