-- Đếm số lần tra cứu provider khi lệnh giải ngân mất kết quả (worker chết khi đang PROCESSING hoặc provider
-- trả kết quả không chắc chắn). Hết số lần cho phép thì dừng tự động để vận hành đối soát; không chuyển tiền lại.
ALTER TABLE payment_disbursements
    ADD COLUMN reconciliation_attempt_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE payment_disbursements
    ADD CONSTRAINT ck_payment_disbursement_reconciliation_attempts CHECK (reconciliation_attempt_count >= 0);

-- Bản ghi đang chờ đối soát từ trước migration chưa có lịch tra cứu; xếp lịch ngay để worker mới xử lý.
UPDATE payment_disbursements
SET next_attempt_at = updated_at
WHERE status = 'RECONCILIATION_REQUIRED' AND next_attempt_at IS NULL;
