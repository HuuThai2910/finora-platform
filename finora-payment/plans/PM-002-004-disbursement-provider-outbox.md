---
document_type: TASK_PLAN
task_id: PM-002-004
owner: Thai
reviewers: [Hai]
status: IN_PROGRESS
updated_at: 2026-09-27
---

# Payment provider, disbursement và transactional outbox

## Mục tiêu

Payment nhận `DisbursementRequested.v1`, lưu yêu cầu duy nhất theo `sagaId`, gọi provider ngoài DB transaction và phát một trong hai kết quả qua outbox:

- `DisbursementCompleted.v1`: provider đã xác nhận chuyển tiền và có `paymentReference`.
- `DisbursementFailed.v1`: lỗi cuối cùng sau retry hoặc lỗi cấu hình/business không retry.

## Provider

- `mock` là mặc định local/demo, tạo reference ổn định từ `sagaId` và không chuyển tiền thật.
- `zalopay` fail closed cho đến khi có AppId/key, callback HTTPS công khai và quyền API phù hợp.
- Đổi provider bằng `PAYMENT_PROVIDER`, không đổi Loan/Investment event contract.

## Invariant

- Một `sagaId` chỉ có một payment disbursement.
- Callback/query trùng phải trả lại kết quả cũ; không chuyển tiền hai lần.
- Provider success phải được lưu trước khi phát event.
- FINORA ledger là projection/đối soát, không được mô tả là tài khoản giữ tiền thật.

## Còn phải nghiệm thu

- Bổ sung capture ledger dựa trên hold thật khi Investment bỏ `StubPaymentClient`.
- Contract test/callback/query với ZaloPay sandbox sau khi được cấp credential.
- Test restart, Kafka duplicate, timeout sau provider success và DLT/reconciliation.
