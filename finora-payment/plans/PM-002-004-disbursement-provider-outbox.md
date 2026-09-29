---
document_type: TASK_PLAN
task_id: PM-002-004
owner: Thai
reviewers: [Hai]
status: IN_PROGRESS
updated_at: 2026-09-28
---

# Payment provider, disbursement và transactional outbox

## Mục tiêu

Payment nhận `DisbursementRequested.v2`, lưu yêu cầu duy nhất theo `sagaId`, gọi provider ngoài DB transaction và phát một trong hai kết quả qua outbox:

- `DisbursementCompleted.v1`: provider đã xác nhận chuyển tiền và có `paymentReference`.
- `DisbursementFailed.v1`: lỗi cuối cùng sau retry hoặc lỗi cấu hình/business không retry.

## Provider

- `mock` là mặc định local/demo, tạo reference ổn định từ `sagaId` và không chuyển tiền thật.
- `zalopay` fail closed cho đến khi có AppId/key, callback HTTPS công khai và quyền API phù hợp.
- Đổi provider giải ngân bằng `PAYMENT_DISBURSEMENT_PROVIDER`, độc lập với `PAYMENT_TOPUP_PROVIDER`.

## Invariant

- Một `sagaId` chỉ có một payment disbursement.
- Callback/query trùng phải trả lại kết quả cũ; không chuyển tiền hai lần.
- Provider success phải được lưu trước khi phát event.
- Provider success chỉ được phát kết quả sau khi tiền capture đã được ghi có vào ví người vay bằng
  bút toán cân bằng, idempotent theo `sagaId`.
- Disbursement legacy đã `COMPLETED` nhưng thiếu bút toán ví người vay được worker tạo bút toán bù
  `clearing → borrower AVAILABLE`; tuyệt đối không update/delete ledger lịch sử.
- FINORA ledger là projection/đối soát, không được mô tả là tài khoản giữ tiền thật.

## Còn phải nghiệm thu

- Capture ledger theo `paymentHoldReference` và Investment HTTP adapter: **đã triển khai**.
- Contract test/callback/query với ZaloPay sandbox sau khi được cấp credential.
- Test restart, Kafka duplicate, timeout sau provider success và DLT/reconciliation.
