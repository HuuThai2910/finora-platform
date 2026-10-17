---
document_type: TASK_PLAN
task_id: LN-015
owner: Thai
reviewers:
  - Hai
status: REVIEW
depends_on:
  - LN-012
  - LN-013
updated_at: 2026-10-11
---

# LN-015 — Cơ cấu kỳ hạn và gia hạn khoản vay

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

Người vay có thể gửi một yêu cầu điều chỉnh kỳ hạn hoặc gia hạn khi không còn phù hợp với lịch hiện tại.
Yêu cầu không tự động đổi lịch: FINORA thẩm định, toàn bộ nhà đầu tư đang nắm Note đồng ý, sau đó
Apache Fineract mới tạo và phê duyệt lịch mới.
Trong khi chờ, lịch cũ vẫn có hiệu lực và vẫn có thể phát sinh quá hạn.

### Ai tham gia và nhìn thấy gì?

- Người vay tạo yêu cầu cho khoản vay của mình và theo dõi trạng thái.
- Admin thẩm định đạt hoặc từ chối; quyết định có idempotency và audit.
- Investment snapshot chủ Note/dư nợ tại thời điểm mở biểu quyết; mỗi nhà đầu tư tự đồng ý hoặc từ chối.
- Loan giữ vòng đời yêu cầu và khoản vay. Fineract là nguồn chuẩn của lịch sau khi cơ cấu.
- Investment nhận `LoanRescheduleConsentRequested.v1`, phát `LoanRescheduleConsentCompleted.v1`, rồi
  tiếp tục nhận `LoanRescheduled.v1` sau khi Fineract xác nhận lịch mới.

### Luồng chính

```text
Borrower gửi yêu cầu -> PENDING_REVIEW (lịch cũ vẫn chạy)
Admin thẩm định đạt -> PENDING_INVESTOR_CONSENT + LoanRescheduleConsentRequested.v1
Investment chốt chủ Note -> từng investor APPROVED/REJECTED
Tất cả đồng ý -> LoanRescheduleConsentCompleted.v1 -> loan RESTRUCTURING + request CREATE_PENDING
Worker reconcile/create reschedule ở Fineract -> APPROVAL_PENDING
Worker reconcile/approve ở Fineract -> đọc servicing snapshot mới
Local transaction: request COMPLETED + loan ACTIVE + projection mới + outbox LoanRescheduled.v1
```

Admin từ chối đưa yêu cầu sang `REJECTED`; một nhà đầu tư từ chối đưa sang `INVESTOR_REJECTED`.
Cả hai trường hợp đều không đổi trạng thái khoản vay hoặc lịch Fineract.

### Dữ liệu quan trọng

| Dữ liệu | Người dùng hiểu là gì | Nguồn | Ảnh hưởng |
|---|---|---|---|
| `requestType` | Điều chỉnh kỳ hạn hay gia hạn | Borrower | Quyết định field nào bắt buộc |
| `rescheduleFromDate` | Kỳ bắt đầu áp dụng đề nghị | Borrower | Không được ở quá khứ |
| `adjustedDueDate/extraTerms` | Ngày kỳ mới hoặc số kỳ kéo dài | Borrower | Payload Fineract và ngày đáo hạn mới |
| `reasonComment` | Lý do nghiệp vụ | Borrower | Chỉ admin/borrower xem; không đưa vào Kafka/log |
| `termsVersion/termsHash` | Nội dung borrower đã xác nhận | Loan | Bằng chứng consent bất biến |
| `fineractRescheduleId` | Yêu cầu tương ứng trong core | Fineract | Reconcile sau timeout/restart |

### Failure và ví dụ

Ví dụ khoản vay đáo hạn 31-12, borrower xin thêm 2 kỳ. Yêu cầu chờ duyệt không dời ngày 31-12. Sau khi admin
duyệt và Fineract xác nhận, projection mới đổi ngày đáo hạn; event mới được phát. Nếu timeout sau create,
worker tìm marker `requestId` tại Fineract trước khi tạo lại nên không sinh hai yêu cầu.

## 1. Phạm vi

Trong phạm vi: request/query borrower, thẩm định admin, đồng thuận investor, durable worker, Fineract
reschedule API, projection, ba event, migration, web quản trị và mobile borrower/investor. Ngoài phạm vi:
tự động phê duyệt, tự miễn lãi/phí và quyết định pháp lý production.

Áp dụng `LEGAL-SERVICING-01` trong [LEGAL-COMPLIANCE](../../docs/LEGAL-COMPLIANCE.md). Việc dùng benchmark
ngân hàng cho P2P production vẫn là `LEGAL-OPEN-05` và cần legal review.

## 2. Entity, quan hệ và invariant

```text
FinoraLoan (1) ---- (0..N) LoanRescheduleRequest
      |
      +---- (1) LoanServicingProjection
```

- FK `loan_reschedule_requests.finora_loan_id -> finora_loans.id`; không cascade xóa lịch sử.
- Unique `(borrower_id, idempotency_key)` bảo vệ retry cùng request.
- Partial unique index bảo vệ tối đa một yêu cầu chưa kết thúc trên mỗi loan.
- Yêu cầu `PENDING_REVIEW` không đổi `FinoraLoan.status`.
- Admin thẩm định đạt chưa đổi loan. Chỉ kết quả đồng thuận toàn bộ mới đổi loan
  `ACTIVE/DEFAULTED -> RESTRUCTURING`; hoàn tất mới đổi lại `ACTIVE`.

Trạng thái request:

```text
PENDING_REVIEW -> REJECTED
PENDING_REVIEW -> PENDING_INVESTOR_CONSENT -> INVESTOR_REJECTED
PENDING_INVESTOR_CONSENT -> CREATE_PENDING -> CREATING -> APPROVAL_PENDING
APPROVAL_PENDING -> APPROVING -> COMPLETED
CREATING/APPROVING -> RECONCILIATION_REQUIRED -> CREATING/APPROVING
                                    -> MANUAL_REVIEW (hết retry/lỗi vĩnh viễn)
```

## 3. API và quyền

- `POST /api/v1/loans/{loanNumber}/reschedule-requests`, borrower, bắt buộc `Idempotency-Key`.
- `GET /api/v1/loans/{loanNumber}/reschedule-requests`, borrower, phân trang.
- `GET /api/v1/admin/loan-reschedule-requests`, admin, phân trang và lọc status.
- `POST /api/v1/admin/loan-reschedule-requests/{requestId}/approve`, admin, idempotent.
- `POST /api/v1/admin/loan-reschedule-requests/{requestId}/reject`, admin, idempotent.
- `GET /api/v1/investments/reschedule-consents`, investor, danh sách phiếu của mình.
- `POST /api/v1/investments/reschedule-consents/{requestId}/approve|reject`, investor, idempotent.

Response không trả raw Fineract payload hoặc secret.

## 4. Transaction, external call và vận hành

| Cơ chế | Vấn đề cần ngăn | Dữ liệu kiểm soát | Khi lỗi/hết hạn |
|---|---|---|---|
| Idempotency + request hash | Mobile retry tạo hai request | borrower/key/hash unique | Cùng hash trả request cũ; khác hash trả conflict |
| Partial unique index | Hai request đồng thời cùng loan | status chưa terminal | Database từ chối request thứ hai |
| Processing lease | Worker chết giữa external call | status, `processingStartedAt` | Sau lease worker khác tiếp quản nhưng reconcile trước |
| Marker Fineract | Timeout sau create làm tạo trùng | `[FINORA:<requestId>]` | Tìm request core trước khi POST create |
| Local transaction + outbox | Event phát trước dữ liệu commit | request/loan/projection/outbox | Commit hoặc rollback cùng nhau |
| Bounded retry | Dependency lỗi vô hạn | attempt/nextAttemptAt/maxAttempts | `MANUAL_REVIEW`, không tự đoán kết quả |

Mọi HTTP Fineract nằm ngoài transaction database. Worker claim trong transaction ngắn, gọi mạng, rồi hoàn tất
trong transaction ngắn khác.

## 5. Event

`LoanRescheduleConsentRequested.v1` mở biểu quyết; `LoanRescheduleConsentCompleted.v1` trả kết quả
đồng thuận về Loan. `LoanRescheduled.v1` dùng `loanApplicationId` làm partition key và chỉ chứa logical IDs, loại yêu cầu,
ngày cũ/mới, tham số lịch và `occurredAt`; không chứa lý do tự do hoặc PII. Contract chi tiết nằm trong
`docs/integrations/LOAN-INVESTMENT-EVENTS.md` và `.agents/rules/08-cross-service-flows.md`.

## 6. Query, index và hiệu năng

- `(borrower_id, loan_number, created_at desc, id desc)` phục vụ danh sách borrower.
- `(status, created_at desc, id desc)` phục vụ admin.
- `(status, next_attempt_at, id)` phục vụ bounded worker batch.
- API dùng pagination; worker chỉ lấy ID trước rồi lock từng bản ghi, không giữ transaction trong HTTP call.

## 7. Kiểm thử và nghiệm thu

- Domain: state transition, validation theo loại yêu cầu, lease/retry/manual review.
- Service: ownership, idempotency, concurrent active request, approve/reject.
- Adapter: list/reconcile, create, approve, parse Fineract 1.15.
- Worker: timeout sau create/approve, restart và projection/outbox chỉ hoàn tất một lần.
- Migration upgrade và full `mvn -pl finora-loan -am verify` xanh.

## 8. Bản đồ code thực tế

### 8.1 API → hàm

| API/worker | Luồng code thực tế | Dữ liệu thay đổi |
|---|---|---|
| `GET /api/v1/loans/reschedule-policy` | `LoanRescheduleController.policy` → `LoanRescheduleService.policy` | Read-only version/text/hash |
| `POST /api/v1/loans/{loanNumber}/reschedule-requests` | controller → `submit` → `LoanRescheduleRequest.submit` → repository | Tạo `PENDING_REVIEW`; không đổi loan |
| `GET .../reschedule-requests` | controller → `listMine` → paged repository | Read-only, kiểm tra owner |
| `POST /api/v1/admin/loan-reschedule-requests/{id}/approve` | admin controller → `approve` → outbox consent request | Request `PENDING_INVESTOR_CONSENT`; loan/lịch giữ nguyên |
| `.../reject` | admin controller → `reject` | Request `REJECTED`; lịch/loan giữ nguyên |
| `POST /api/v1/investments/reschedule-consents/{id}/approve|reject` | Investment controller → consent service → outbox completion | Đồng thuận toàn bộ hoặc kết thúc do từ chối |
| Kafka completion consumer | `LoanRescheduleConsentResultHandler` | Đồng ý: loan `RESTRUCTURING`, request `CREATE_PENDING`; từ chối: `INVESTOR_REJECTED` |
| scheduled worker | `LoanRescheduleWorker` → `LoanRescheduleExecutionService` → `FineractRescheduleGateway`/`FineractServicingGateway` | Request state, projection, loan `ACTIVE`, outbox |

### 8.2 Transaction và failure path thực tế

- `submit/approve/reject` và quyết định investor là local write transaction; API Fineract không nằm trong các method này.
- Worker dùng `TransactionTemplate`: `claim()` commit trước HTTP; `created/complete/fail` là transaction ngắn sau HTTP.
- `executeCreate()` gọi `findByLoanAndMarker()` trước `create()` nên timeout/restart không POST mù.
- `executeApprove()` đọc status trước; nếu core đã approve thì chỉ đọc snapshot và finalize.
- Lỗi retryable về `RECONCILIATION_REQUIRED` với backoff; hết số lần hoặc lỗi vĩnh viễn về `MANUAL_REVIEW`.
- `complete()` khóa request/loan/projection và ghi `LoanRescheduled.v1` bằng outbox trong cùng transaction.

### 8.3 Bằng chứng kiểm thử

- `LoanRescheduleRequestTest`: approval gate, processing lease, bounded retry, shape theo loại yêu cầu.
- `FineractRescheduleHttpClientTest`: reconcile marker, create/approve contract Fineract 1.15.
- Ngày 2026-10-11: reactor compile Loan/Payment/Investment thành công; test domain Loan (4), mapper
  Fineract (10), fee policy Payment (5) và domain Investment consent/Note (5) đều xanh.

Không lưu `lastStep` riêng; `fineractRescheduleId == null` là dấu hiệu bền vững để reconcile bước CREATE,
có ID là bước APPROVE. Notification vẫn là consumer phụ trợ, không nằm trong transaction cơ cấu.
