---
document_type: TASK_PLAN
task_id: PM-006
owner: Thai
reviewers: []
status: DONE
updated_at: 2026-10-04
---

# PM-006 — Đối soát giao dịch trả nợ

## Mục tiêu

Khi Payment đã trừ ví nhưng mất kết quả Fineract, vận hành phải nhìn thấy giao dịch và chủ động tra cứu
lại theo `FINORA-REPAY-<repaymentId>`. Thao tác đối soát chỉ đọc Fineract; không POST repayment lần hai
và không hoàn tiền khi kết quả core còn chưa xác định.

## API và quyền

- `GET /api/v1/admin/repayment-reconciliation?page=0&size=20`: danh sách
  `CORE_POSTING`, `RECONCILIATION_REQUIRED`, `FAILED`, cũ nhất trước.
- `POST /api/v1/admin/repayment-reconciliation/{repaymentId}/reconcile`: chỉ nhận
  `RECONCILIATION_REQUIRED`, gọi đường tra cứu external reference hiện có.
- Cả hai yêu cầu `ROLE_ADMIN`; người vay/nhà đầu tư nhận `403 ADMIN_ROLE_REQUIRED`.

Response cho phép vận hành đối chiếu `repaymentId`, application/borrower/Fineract ID, loại giao dịch,
amount/currency/date, ledger transaction, Fineract transaction, số lần thử, mã/lý do lỗi và `updatedAt`.
Không trả raw response, token hoặc dữ liệu định danh nhạy cảm.

## Idempotency và failure

- Reconcile không tạo side effect tài chính mới; gọi lặp chỉ query cùng external reference.
- Nếu tìm thấy transaction, state chuyển `CORE_POSTED`; worker phân phối tiếp theo đường cũ.
- Nếu chưa tìm thấy hoặc query timeout, giữ `RECONCILIATION_REQUIRED`; không đoán thất bại.
- `FAILED` chỉ hiển thị để điều tra; endpoint không tự retry một lỗi đã xác định.

## Kiểm chứng

- 4 unit test: quyền admin, incident filter, status gate và gọi đúng read-only recovery path.
- Full Payment verify: 36 unit + 10 integration test, PostgreSQL 17/Flyway V1→V8, không failure/error.
- Integration happy case chứng minh tiền đi borrower → clearing → current Note owner, core breakdown cân
  bằng, Note giảm gốc và outbox `RepaymentDistributed` được tạo.

