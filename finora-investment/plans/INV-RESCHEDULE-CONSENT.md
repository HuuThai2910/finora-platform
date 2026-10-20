---
document_type: TASK_PLAN
task_id: INV-RESCHEDULE-CONSENT
owner: Hai
reviewers:
  - Thai
status: REVIEW
depends_on:
  - LN-015
updated_at: 2026-10-11
---

# INV-RESCHEDULE-CONSENT — Nhà đầu tư đồng ý cơ cấu khoản vay

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

FINORA không tự thay đổi lịch đã ký chỉ bằng quyết định của admin. Sau khi admin đánh giá đề nghị của
người vay là hợp lý, mỗi nhà đầu tư còn sở hữu Note của khoản vay được xem đúng phương án và tự đồng ý
hoặc từ chối. Chỉ khi tất cả cùng đồng ý, Loan mới yêu cầu Fineract lập lịch mới.

### Ai tham gia và nhìn thấy gì?

- Người vay gửi một trong hai phương án: điều chỉnh một kỳ trong thời hạn cũ, hoặc kéo dài ngày đáo hạn.
- Admin chỉ **thẩm định đạt** để mở bước xin ý kiến, không áp dụng lịch.
- Nhà đầu tư thấy mã khoản vay, phương án, ngày bắt đầu và phần gốc Note của mình tại lúc mở biểu quyết.
- Một người từ chối thì đề nghị kết thúc; lịch hiện tại tiếp tục có hiệu lực.

### Luồng chính và failure

```text
Loan phát LoanRescheduleConsentRequested.v1
→ Investment chụp danh sách chủ Note còn dư nợ
→ mỗi nhà đầu tư có một phiếu PENDING
→ tất cả APPROVED
→ Investment phát LoanRescheduleConsentCompleted.v1 approved=true
→ Loan/Fineract áp dụng lịch mới
```

Nếu event đến trùng, `processed_events` chặn tạo phiếu lần hai. Nếu một nhà đầu tư từ chối, phiếu còn
chờ chuyển `CANCELLED` và event kết quả `approved=false` được phát đúng một lần. Kafka lỗi chỉ làm outbox
retry; không làm mất quyết định đã ghi.

## 1. Phạm vi và ownership

Investment sở hữu phiếu, chủ Note và quyết định của nhà đầu tư. Loan sở hữu yêu cầu cơ cấu và trạng thái
khoản vay. Fineract sở hữu lịch trả chính thức. Không service nào đọc database của service khác.

Ngoài phạm vi: bỏ phiếu theo tỷ lệ đa số, chuyển nhượng quyền biểu quyết sau snapshot, tự động đồng ý,
hoặc miễn nghĩa vụ đang đến hạn. Policy hiện tại yêu cầu 100% chủ Note trong snapshot đồng ý.

Áp dụng `LEGAL-SERVICING-01`: cổng 100% là policy sản phẩm nhiều bên của FINORA, không phải thủ tục pháp
luật mặc định. Lịch cũ vẫn chạy trong thời gian chờ.

## 2. Dữ liệu và quan hệ

```text
LoanRescheduleRequest (Loan, logical) 1 ───── 1..N InvestorRescheduleConsent (Investment)
InvestmentNote                       1..N ─── snapshot theo investorId + outstandingPrincipal
InvestorRescheduleConsent            N ───── 1 nhà đầu tư Keycloak (logical, không FK)
```

| Field | Người dùng hiểu là gì | Nguồn/dùng ở đâu | Khi sai hoặc thiếu |
|---|---|---|---|
| `requestId` | Mã đề nghị | Loan event; idempotency và gom phiếu | Từ chối event |
| `requestType` | Điều chỉnh kỳ hay gia hạn | Loan event/UI | Shape DB và handler chặn sai |
| `rescheduleFromDate` | Kỳ bắt đầu đổi | Loan event/UI | Bắt buộc |
| `adjustedDueDate/extraTerms` | Ngày mới hoặc số kỳ thêm | Loan event/UI/Fineract qua Loan | Chỉ đúng một field theo loại |
| `investorId` | Người có quyền quyết định | Chủ Note tại snapshot | API chỉ đọc/ghi phiếu của chính actor |
| `outstandingPrincipal` | Phần vốn chịu ảnh hưởng | Cộng dư gốc Note ACTIVE/DEFAULTED | Phải dương và bất biến |
| `status` | Chờ/đồng ý/từ chối/kết thúc | Domain/UI/event tổng hợp | Chỉ chuyển một lần khỏi PENDING |
| `decisionIdempotencyKey` | Dấu vết lần bấm | Mobile/API | Retry cùng key trả kết quả cũ |

Database bảo vệ unique `(request_id, investor_id)`, shape theo `request_type`, dư gốc dương và enum trạng
thái. Không cascade với Note vì đây là snapshot lịch sử độc lập.

## 3. API và event contract

- `GET /api/v1/investments/reschedule-consents?page=0&size=20`: danh sách của actor, tối đa 100/trang.
- `GET /api/v1/investments/reschedule-consents/{requestId}`: chi tiết đúng chủ phiếu.
- `POST .../{requestId}/approve|reject`: cần `Idempotency-Key`; comment tối đa 500 ký tự.
- Consume `LoanRescheduleConsentRequested.v1` từ `finora.loan.reschedule-consent-requested`.
- Produce `LoanRescheduleConsentCompleted.v1` qua outbox tới
  `finora.investment.reschedule-consent-completed`.

Event không chứa tên, CCCD, email hoặc lý do riêng của người vay. Partition/correlation dùng `requestId`.

## 4. Query, index và N+1

- Danh sách cá nhân dùng `(investor_id, status, created_at DESC)`, có phân trang và không load Note.
- Quyết định khóa mọi phiếu của một `request_id` theo thứ tự ID bằng pessimistic write lock; index
  `(request_id, status)` hỗ trợ tập hợp và đếm trạng thái.
- Consumer đọc Notes của một loan theo truy vấn repository hiện có rồi gom theo investor trong bộ nhớ;
  không gọi REST hoặc query từng nhà đầu tư.

## 5. Transaction, concurrency và idempotency

Consumer tạo toàn bộ phiếu và `processed_events` trong cùng transaction. API quyết định khóa cả nhóm,
ghi quyết định và outbox kết quả trong cùng transaction. Hai nhà đầu tư bấm gần nhau được tuần tự hóa;
người cuối cùng đồng ý mới phát kết quả thành công. Hai lần bấm cùng key không tạo outbox thứ hai.

Outbox publish nằm ngoài transaction nghiệp vụ, có retry/lease sẵn của Investment. Loan consumer xử lý
event kết quả idempotent; event phụ trợ lỗi không được tự đổi Note hoặc lịch trả.

## 6. Bảo mật và audit

Identity lấy từ JWT qua `SecurityUtils`; repository luôn lọc `investorId`. Client không được gửi investor
ID hay dư nợ. Event và log chỉ dùng business ID; không ghi comment/PII vào Kafka hoặc log. Quyết định,
thời điểm, key và optimistic version là audit cục bộ.

## 7. Migration và rollout

- V14 chỉ thêm bảng/index/constraint; không sửa migration đã chạy.
- Producer Loan và consumer Investment phải bật cùng hai topic đã đăng ký trong cấu hình Compose.
- Mobile mới mở màn biểu quyết trong tab Ví. Web admin chỉ thẩm định đạt; không thay nhà đầu tư quyết định.
- Owner Hải phải review module Investment và vùng chung trước merge.

## 8. Test và nghiệm thu

- Domain: approve/reject/cancel, quyết định lần hai bị chặn, idempotency key được giữ.
- Contract: event thiếu field/shape sai bị từ chối; event trùng không tạo phiếu trùng.
- Concurrency: hai quyết định gần nhau chỉ có một completion outbox.
- Authorization: investor khác không xem hoặc quyết định được phiếu.
- UI: empty/loading/error, xác nhận cuối, approve/reject và refresh trạng thái.

## 9. Bản đồ code thực tế

- Consumer → `LoanRescheduleConsentRequestedHandler.handle()` → `InvestmentNoteRepository` →
  `InvestorRescheduleConsentRepository` + `ProcessedEventRepository`.
- REST → `InvestorRescheduleConsentController` → `InvestorRescheduleConsentService.decide()` → domain
  `approve/reject/cancel()` → `InvestmentOutboxService.record()`.
- Publisher allowlist → `InvestmentKafkaOutboxPublisher` → topic completion.
- Mobile UI mới → `RescheduleConsentsScreen` → `getRescheduleConsents/decideRescheduleConsent`.

## 10. Sai khác và bằng chứng hiện tại

Implementation dùng unanimity theo từng chủ Note, không weighted-majority. Snapshot gom nhiều Note cùng
investor thành một phiếu và tổng dư gốc. Compile reactor Loan/Payment/Investment và unit test domain đã
pass ngày 2026-10-11; integration Kafka/PostgreSQL và owner review vẫn là cổng trước `ACCEPTED`.
