-- R2 (Wave 2D): admin yêu cầu tra cứu lại provider cho lệnh giải ngân RECONCILIATION_REQUIRED đã hết lượt tự động. Thao
-- tác chỉ hẹn lại lần tra cứu theo sagaId, không bao giờ gọi chuyển tiền lần nữa. Lưu ai yêu cầu và lúc nào.
ALTER TABLE payment_disbursements
    ADD COLUMN manual_recheck_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_manual_recheck_by VARCHAR(100),
    ADD COLUMN last_manual_recheck_at TIMESTAMPTZ;

ALTER TABLE payment_disbursements
    ADD CONSTRAINT ck_payment_disbursements_manual_recheck CHECK (
        manual_recheck_count >= 0 AND ((last_manual_recheck_at IS NULL) = (last_manual_recheck_by IS NULL))
    );

-- Màn sự cố của admin lọc lệnh chưa kết thúc theo trạng thái.
CREATE INDEX idx_payment_disbursements_incident_queue
    ON payment_disbursements (status, updated_at, id)
    WHERE status IN ('PROCESSING', 'RETRY_PENDING', 'RECONCILIATION_REQUIRED');
