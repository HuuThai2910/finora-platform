---
document_type: TASK_PLAN
task_id: LN-011
owner: Thai
reviewers:
  - Hai
status: IN_PROGRESS
depends_on:
  - LN-010
  - Payment disbursement contract
  - Fineract client/loan fixture
updated_at: 2026-09-27
---

# LN-011 — Fineract loan booking và Disbursement Saga

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

Khi đã gọi đủ vốn, hệ thống phải phối hợp Investment, Fineract và Payment để tạo khoản vay core rồi chuyển tiền cho borrower. Không service nào có một transaction chung, nên LN-011 dùng Saga: một chuỗi bước có trạng thái bền vững, có thể tiếp tục đúng chỗ sau timeout hoặc restart.

### Ai tham gia và chịu trách nhiệm gì?

- Loan điều phối thứ tự và sở hữu trạng thái Saga/khoản vay FINORA.
- Investment khóa/chốt các commitment đã góp đủ vốn.
- Fineract tạo Client/Loan, approve và ghi nhận disbursement core.
- Payment chuyển tiền, sở hữu ledger và xác nhận bằng `paymentReference`.
- Borrower chỉ thấy “đang giải ngân”, “đã giải ngân” hoặc “cần xử lý”; không phải chạy lại thủ công toàn bộ chuỗi.

### Luồng chính dễ đọc

```text
FUNDED → hai bên ký đủ → Contract EFFECTIVE → yêu cầu Payment chuyển tiền
→ nhận xác nhận tiền → bảo đảm Client và Loan Fineract
→ approve/disburse core → LoanDisbursed → Investment phát hành Note
```

Mỗi bước được lưu trước khi sang bước tiếp. Gửi lại cùng request dùng cùng idempotency key nên không tạo hai Client/Loan hoặc chuyển tiền hai lần.

### Dữ liệu quan trọng và ảnh hưởng

| Dữ liệu | Ý nghĩa nghiệp vụ | Ảnh hưởng |
|---|---|---|
| `sagaId/step/status` | Quy trình nào đang ở bước nào | Worker tiếp tục đúng bước sau restart |
| `fineractClientId` | Borrower tương ứng trong core | Một borrower không bị tạo Client trùng |
| `fineractLoanId` | Khoản vay chính thức trong core | Dùng cho lịch trả, balance và repayment về sau |
| `paymentReference` | Bằng chứng Payment đã chuyển tiền | Không được gọi core disburse trước khi có kết quả hợp lệ |
| request/idempotency key | Mã một hành động logic | Retry không lặp side effect tài chính |
| error/nextRetryAt/attempt | Lỗi và thời điểm thử lại | Phân biệt retry tự động với manual repair |

### Trường hợp lỗi quan trọng

- Payment thất bại trước khi tiền đi: dừng/compensate theo contract với Investment.
- Payment đã chuyển nhưng Fineract chưa ghi disbursement: vào `REPAIR_REQUIRED`, không thu/chuyển lại và không đánh `ACTIVE` giả.
- Timeout không đồng nghĩa thất bại: phải query/reconcile theo business reference trước khi gửi command lại.

### Ví dụ và điều kiện hoàn thành

Khoản vay 50 triệu đã funded. Payment trả `PAY-001`; Fineract disburse thành công ID core 9001; Loan mới chuyển `ACTIVE`. Nếu ứng dụng restart sau Payment success, worker phải tiếp tục từ bước Fineract disburse với `PAY-001`, không chuyển thêm 50 triệu. Task hoàn tất khi test restart/timeout/concurrent worker tại từng bước chứng minh điều này.

### Đối chiếu pháp lý

- Áp dụng `LEGAL-PAYMENT-01`, `LEGAL-CONTRACT-01` và `LEGAL-DATA-01` trong [Sổ đối chiếu pháp lý FINORA](../../docs/LEGAL-COMPLIANCE.md). Theo [Điều 11 Nghị định 94/2025/NĐ-CP](https://vanban.chinhphu.vn/?classid=1&docid=213519&pageid=27160), giải ngân/thanh toán trong giải pháp P2P sandbox phải đi qua tài khoản tại tổ chức tín dụng/chi nhánh ngân hàng nước ngoài hoặc ví điện tử tại tổ chức trung gian thanh toán.
- Chỉ giải ngân hợp đồng đã ký, chưa hết hạn và khớp đúng final terms. `paymentReference`/Fineract transaction ID là bằng chứng đối soát, không được thay bằng trạng thái Loan tự suy đoán.
- Chưa có provider/kênh thanh toán được phép thì saga production phải fail closed. Timeout sau khi tiền đã chuyển luôn đi vào query/reconcile/repair, tuyệt đối không gửi lệnh chuyển tiền lần hai.

## 1. Mục tiêu nghiệp vụ

Sau khi Investment xác nhận `FUNDED`, Loan điều phối chuỗi tạo khoản vay core, chuyển tiền cho borrower và kích hoạt khoản vay mà không dùng distributed transaction.

Kết quả cuối:

- Fineract có Client mapping và Loan ID duy nhất theo Contract.
- Payment xác nhận dòng tiền bằng payment reference duy nhất.
- Fineract chỉ được disburse sau khi Payment xác nhận chuyển tiền.
- Contract chuyển `EFFECTIVE` ngay khi đủ chữ ký; Saga/Loan chỉ chuyển `COMPLETED/ACTIVE` khi core đã xác nhận disbursement.
- Mỗi bước có thể tiếp tục an toàn sau timeout/restart.

## 2. Chuỗi Saga

```text
FUNDED + CONTRACT EFFECTIVE
  -> REQUEST_PAYMENT_DISBURSEMENT
  -> WAIT_PAYMENT_RESULT
  -> ENSURE_FINERACT_CLIENT
  -> CREATE_FINERACT_LOAN
  -> APPROVE_FINERACT_LOAN
  -> DISBURSE_FINERACT_LOAN
  -> PUBLISH_LOAN_DISBURSED
  -> COMPLETE: Saga COMPLETED; Investment FINALIZED + Notes ACTIVE
```

Không giữ PostgreSQL transaction khi gọi HTTP. Mỗi bước ghi kết quả bền vững trước khi chạy bước kế tiếp.

Nếu Payment thất bại trước khi chuyển tiền, Saga đi theo compensation/release policy đã chốt với Investment. Nếu tiền đã chuyển nhưng core disburse lỗi, chuyển `CORE_DISBURSEMENT_REPAIR_REQUIRED`; không tự thu hồi tiền và không đánh `ACTIVE` giả.

## 3. Entity và field

> Đây là thiết kế dự kiến. `FinoraLoan`, client/loan mapping và Saga chưa tồn tại trong migration V1–V5; không được để sơ đồ dưới đây khiến người đọc hiểu rằng code đã có.

### `FineractClientMapping`

| Field | Kiểu | Tác dụng |
|---|---|---|
| `borrowerId` | logical ID, unique | Một borrower ánh xạ một client core |
| `fineractClientId` | `BIGINT`, unique | ID do Fineract cấp |
| `externalId` | `VARCHAR(100)`, unique | Business key ổn định, không dùng PII |
| `status` | `VARCHAR(30)` | `PENDING/SYNCED/FAILED` |
| `lastSyncedAt` | `TIMESTAMP(6)` | Đối soát |
| audit/version | chuẩn chung | Truy vết/concurrency |

### `DisbursementSaga`

| Field | Kiểu | Tác dụng |
|---|---|---|
| `sagaId` | `VARCHAR(36)` | UUID điều phối, là business/correlation ID chứ không phải FK |
| `loanId` | `BIGINT`, unique/FK | Một Saga active cho entity `FinoraLoan` |
| `step` | `VARCHAR(50)` | Bước cuối đã xác nhận |
| `status` | `VARCHAR(40)` | RUNNING/WAITING/RETRY/REPAIR/COMPLETED/FAILED |
| `attemptCount` | `INT` | Giới hạn retry |
| `nextRetryAt` | `TIMESTAMP(6)` | Worker chọn job đến hạn |
| `lastRequestId` | `VARCHAR(100)` | Correlation/idempotency |
| `paymentReference` | `VARCHAR(100)`, unique nullable | Kết quả Payment |
| `fineractLoanId` | `BIGINT`, unique nullable | Kết quả core |
| `lastErrorCode/detail` | mã + text đã lọc | Vận hành, không chứa secret/PII |
| timestamps/version | chuẩn chung | Audit/concurrency |

### `FineractCommand`

Mỗi external command lưu `commandId`, type, aggregate ID, `idempotencyKey` unique, request hash, sanitized request/response, status, attempt, error code, `nextRetryAt`, created/completed time. Bảng này phục vụ retry/reconcile, không thay domain outbox.

### Quan hệ dự kiến và trách nhiệm từng thành phần

```text
Borrower (logical ID) ── (0..1) FineractClientMapping

LoanContract (1) ── (0..1) FinoraLoan FUNDED
                            │
                            ├── (0..1 active) DisbursementSaga
                            │                    │
                            │                    └── (1..N) FineractCommand
                            │
                            └── (0..1) FineractLoanMapping

DisbursementSaga ── payment command/event ──► Payment (ledger owner)
FineractCommand  ───────────── REST ─────────► Fineract
```

- `DisbursementSaga` trả lời câu hỏi nghiệp vụ “toàn quy trình đang ở bước nào và còn phải làm gì?”.
- `FineractCommand` trả lời câu hỏi kỹ thuật “một lần gọi Fineract cụ thể đã gửi payload nào, thử mấy lần và nhận gì?”. Một Saga có nhiều command nên không gộp hai bảng.
- `FineractClientMapping` ánh xạ borrower sang Client core và được tái sử dụng cho các loan sau; `FineractLoanMapping` ánh xạ đúng một FinoraLoan/Contract sang loan core.
- Payment reference thuộc kết quả chuyển tiền; Loan giữ reference/evidence tối thiểu, còn ledger thuộc Payment.
- Outbox/Kafka dùng cho command/event qua Investment/Payment. Adapter vẫn gọi Fineract bằng REST; Kafka không thay REST API của Fineract.

## 4. Fineract Client contract draft

```json
{
  "officeId": "${DEFAULT_OFFICE_ID}",
  "firstname": "${MINIMAL_NAME_FROM_USER_CONTRACT}",
  "lastname": "${MINIMAL_NAME_FROM_USER_CONTRACT}",
  "externalId": "BORROWER-001",
  "active": true,
  "activationDate": "2026-08-15",
  "submittedOnDate": "2026-08-15",
  "dateFormat": "yyyy-MM-dd",
  "locale": "en"
}
```

Exact PII tối thiểu phải được Hải xác nhận từ User contract. Retry sau timeout phải tìm/reconcile theo `externalId` trước khi create lại.

## 5. Fineract Loan contract draft

```json
{
  "clientId": 1001,
  "productId": 321,
  "externalId": "CTR-2026-000001",
  "principal": "50000000",
  "loanTermFrequency": 12,
  "loanTermFrequencyType": 2,
  "loanType": "individual",
  "numberOfRepayments": 12,
  "repaymentEvery": 1,
  "repaymentFrequencyType": 2,
  "interestRatePerPeriod": "15.0000",
  "amortizationType": 1,
  "interestType": 0,
  "interestCalculationPeriodType": 0,
  "transactionProcessingStrategyCode": "${MAPPING_STRATEGY_CODE}",
  "expectedDisbursementDate": "2026-08-15",
  "submittedOnDate": "2026-08-15",
  "dateFormat": "yyyy-MM-dd",
  "locale": "en"
}
```

Response allowlist chỉ cần `loanId/resourceId` và changes/status cần thiết.

Approve draft:

```json
{
  "approvedOnDate": "2026-08-15",
  "approvedLoanAmount": "50000000",
  "expectedDisbursementDate": "2026-08-15",
  "dateFormat": "yyyy-MM-dd",
  "locale": "en"
}
```

Disburse chỉ sau Payment success:

```json
{
  "actualDisbursementDate": "2026-08-15",
  "transactionAmount": "50000000",
  "paymentTypeId": "${FINORA_DISBURSEMENT_PAYMENT_TYPE}",
  "note": "FINORA saga reference only",
  "dateFormat": "yyyy-MM-dd",
  "locale": "en"
}
```

Tất cả enum/account/payment type phải khóa bằng template/Swagger/contract test Fineract 1.15; không hard-code ID ví dụ.

## 6. Payment command/event

Request tối thiểu mang `sagaId`, `loanId`, `contractId`, borrower destination reference, amount/currency và idempotency key. Response/event mang `paymentReference`, status, amount, completed time và error code allowlist.

Loan không ghi ledger và không tự kết luận tiền đã chuyển chỉ vì HTTP timeout. Reconcile với Payment theo `sagaId/idempotencyKey`.

## 7. Index, transaction và worker

- Unique `saga.loan_id`, `payment_reference`, `fineract_loan_id`, mapping external ID và command idempotency key chặn side effect tài chính/core bị nhân đôi.
- Partial index `(next_retry_at, saga_id) WHERE status IN ('RUNNING','RETRY')` cho worker; cột thời điểm đứng trước để lấy job đã đến hạn, `saga_id` phá hòa/phân trang ổn định.
- Processing-lease index cho trạng thái đang chạy bị treo nếu worker có cơ chế claim rồi gọi mạng.
- Worker claim bằng lock/version + lease có test concurrent; không để hai node chạy cùng bước. Worker chỉ lấy batch ID, mỗi Saga chạy trong transaction ngắn riêng.
- Local state + command/outbox ghi cùng transaction; network call ngoài transaction. Không có distributed transaction nối PostgreSQL, Payment và Fineract.
- Retry chỉ timeout/5xx/429 và command idempotent; 4xx business chuyển manual/repair.

Ví dụ transaction: transaction A khóa Saga và ghi command `PENDING`; commit; gọi Fineract/Payment; transaction B khóa lại command/Saga, xác minh vẫn đúng step rồi ghi kết quả và bước kế. Nếu ứng dụng chết giữa hai transaction, command bền vững cho worker biết cần query/retry/reconcile.

Các cuộc đua cần test: hai worker claim cùng Saga; timeout nhưng Fineract đã tạo loan; Payment event đến hai lần; Payment success đến khi admin đang repair; worker cũ trả response sau khi lease đã hết. Kết quả cũ phải bị chặn bằng attempt/version/step, không ghi đè trạng thái mới.

Màn admin dùng projection/batch query cho Saga + mapping mới nhất. Không lazy-load commands cho từng Saga và không gọi Fineract/Payment theo từng dòng, tránh N+1 cả database lẫn network.

## 8. Kiểm thử và nghiệm thu

- Happy path hoàn tất qua tất cả bước.
- Timeout sau core đã tạo Client/Loan nhưng trước response: reconcile, không create trùng.
- Payment failed trước chuyển tiền và compensation.
- Payment success nhưng Fineract disburse fail: repair state, không ACTIVE.
- Restart ở từng step tiếp tục đúng chỗ.
- Hai worker không lặp side effect.
- Contract tests với Fineract/Payment fixture; log không lộ credential/PII.
