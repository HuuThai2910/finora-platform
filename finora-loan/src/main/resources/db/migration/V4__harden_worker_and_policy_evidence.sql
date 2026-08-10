-- Lưu policy eligibility cùng evidence để quyết định lịch sử không đổi nghĩa khi cấu hình tuổi thay đổi.
ALTER TABLE borrower_eligibility_checks
    ADD COLUMN policy_version VARCHAR(50) NOT NULL DEFAULT 'BORROWER_ELIGIBILITY_V1';
ALTER TABLE borrower_eligibility_checks
    ALTER COLUMN policy_version DROP DEFAULT;

-- Các index dưới đây khớp đúng điều kiện lọc và thứ tự sort của API/worker hiện tại.
DROP INDEX IF EXISTS idx_loan_products_catalog;
CREATE INDEX idx_loan_products_catalog
    ON loan_products (status, created_at DESC, id DESC);

DROP INDEX IF EXISTS idx_loan_applications_status_created;
CREATE INDEX idx_loan_applications_worker
    ON loan_applications (status, updated_at, id);

DROP INDEX IF EXISTS idx_fineract_commands_retry;
CREATE INDEX idx_fineract_commands_pending
    ON fineract_commands (updated_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_fineract_commands_retry
    ON fineract_commands (next_retry_at, id) WHERE status = 'RETRY_PENDING';
CREATE INDEX idx_fineract_commands_processing_lease
    ON fineract_commands (updated_at, id) WHERE status = 'PROCESSING';

DROP INDEX IF EXISTS idx_credit_assessments_retry;
CREATE INDEX idx_credit_assessments_pending
    ON credit_scoring_assessments (updated_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_credit_assessments_retry
    ON credit_scoring_assessments (next_retry_at, id) WHERE status = 'RETRY_PENDING';
CREATE INDEX idx_credit_assessments_processing_lease
    ON credit_scoring_assessments (updated_at, id) WHERE status = 'PROCESSING';
