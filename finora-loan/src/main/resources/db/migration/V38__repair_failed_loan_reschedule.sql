ALTER TABLE loan_reschedule_requests
    DROP CONSTRAINT ck_loan_reschedule_status;

ALTER TABLE loan_reschedule_requests
    ADD CONSTRAINT ck_loan_reschedule_status CHECK (status IN (
        'PENDING_REVIEW', 'PENDING_INVESTOR_CONSENT', 'INVESTOR_REJECTED', 'REJECTED',
        'CREATE_PENDING', 'CREATING', 'APPROVAL_PENDING', 'APPROVING', 'COMPLETED',
        'RECONCILIATION_REQUIRED', 'MANUAL_REVIEW', 'CANCELLED'
    ));

-- Yêu cầu lỗi từ bản cũ chưa lưu trạng thái trước cơ cấu: suy ra từ DPD projection để admin có thể retry/cancel an toàn.
UPDATE loan_reschedule_requests request
SET previous_loan_status = CASE WHEN projection.days_past_due >= 91 THEN 'DEFAULTED' ELSE 'ACTIVE' END
FROM loan_servicing_projections projection
WHERE request.finora_loan_id = projection.finora_loan_id
  AND request.previous_loan_status IS NULL
  AND request.status IN ('CREATE_PENDING', 'CREATING', 'APPROVAL_PENDING', 'APPROVING',
                         'RECONCILIATION_REQUIRED', 'MANUAL_REVIEW');

-- Không để dữ liệu cũ mắc ở RESTRUCTURING khi worker đã kết luận cần xử lý thủ công.
UPDATE finora_loans loan
SET status = COALESCE(request.previous_loan_status, 'ACTIVE'), updated_at = CURRENT_TIMESTAMP
FROM loan_reschedule_requests request
WHERE request.finora_loan_id = loan.id
  AND request.status = 'MANUAL_REVIEW'
  AND loan.status = 'RESTRUCTURING';
