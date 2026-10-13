-- Investment báo đợt gọi vốn hết hạn mà chưa đủ vốn (LoanFundingExpired.v1).
-- Hồ sơ giữ trạng thái APPROVED; funding_status = EXPIRED đóng bước chờ gọi vốn và không lập Contract.
ALTER TABLE loan_applications
    ADD COLUMN funding_expired_at TIMESTAMPTZ;

ALTER TABLE loan_applications
    DROP CONSTRAINT ck_loan_applications_funding_status;

ALTER TABLE loan_applications
    ADD CONSTRAINT ck_loan_applications_funding_status
        CHECK (funding_status IS NULL OR funding_status IN ('REQUESTED', 'FULLY_FUNDED', 'EXPIRED')),
    ADD CONSTRAINT ck_loan_applications_funding_expired_complete
        CHECK (funding_status <> 'EXPIRED'
            OR (investment_listing_id IS NOT NULL AND funding_expired_at IS NOT NULL));
