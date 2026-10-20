-- Đợt gọi vốn đã đủ vốn nhưng không giải ngân (hợp đồng bị từ chối/hết hạn ký hoặc Payment báo giải ngân
-- thất bại khi tiền chưa chuyển). Hồ sơ giữ APPROVED; funding_status = CANCELLED đánh dấu đã phát
-- LoanFundingCancelled.v1 để Investment trả tiền đang giữ cho nhà đầu tư.
ALTER TABLE loan_applications
    ADD COLUMN funding_cancelled_at TIMESTAMPTZ,
    ADD COLUMN funding_cancel_reason VARCHAR(40);

ALTER TABLE loan_applications
    DROP CONSTRAINT ck_loan_applications_funding_status;

ALTER TABLE loan_applications
    ADD CONSTRAINT ck_loan_applications_funding_status
        CHECK (funding_status IS NULL
            OR funding_status IN ('REQUESTED', 'FULLY_FUNDED', 'EXPIRED', 'CANCELLED')),
    ADD CONSTRAINT ck_loan_applications_funding_cancelled_complete
        CHECK (funding_status <> 'CANCELLED'
            OR (investment_listing_id IS NOT NULL
                AND allocation_hash IS NOT NULL
                AND funding_cancelled_at IS NOT NULL
                AND funding_cancel_reason IS NOT NULL));
