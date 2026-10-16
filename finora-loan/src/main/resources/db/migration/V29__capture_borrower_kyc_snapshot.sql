-- W2-KYC (D4): eKYC và tuổi của người vay chụp từ finora-user đúng lúc nộp hồ sơ. Worker kiểm điều kiện/AI chạy nền
-- không có token người vay nên đọc lại bản này thay vì gọi User. Hồ sơ nộp trước migration để trống toàn bộ cột.
-- Không lưu ngày sinh, họ tên hay CCCD (LEGAL-DATA-01); không cần index vì chỉ đọc theo khóa chính của hồ sơ.
ALTER TABLE loan_applications
    ADD COLUMN kyc_snapshot_status VARCHAR(30),
    ADD COLUMN kyc_snapshot_age SMALLINT,
    ADD COLUMN kyc_snapshot_reference VARCHAR(100),
    ADD COLUMN kyc_snapshot_version VARCHAR(50),
    ADD COLUMN kyc_snapshot_income_status VARCHAR(30),
    ADD COLUMN kyc_snapshot_source VARCHAR(30),
    ADD COLUMN kyc_snapshot_captured_at TIMESTAMPTZ;

ALTER TABLE loan_applications
    ADD CONSTRAINT ck_loan_application_kyc_snapshot_complete CHECK (
        (kyc_snapshot_status IS NULL AND kyc_snapshot_age IS NULL AND kyc_snapshot_reference IS NULL
            AND kyc_snapshot_version IS NULL AND kyc_snapshot_income_status IS NULL
            AND kyc_snapshot_source IS NULL AND kyc_snapshot_captured_at IS NULL)
        OR (kyc_snapshot_status IS NOT NULL AND kyc_snapshot_income_status IS NOT NULL
            AND kyc_snapshot_source IS NOT NULL AND kyc_snapshot_captured_at IS NOT NULL)
    ),
    ADD CONSTRAINT ck_loan_application_kyc_snapshot_status CHECK (
        kyc_snapshot_status IS NULL
        OR kyc_snapshot_status IN ('VERIFIED', 'PENDING', 'PROCESSING', 'REJECTED', 'EXPIRED')
    ),
    ADD CONSTRAINT ck_loan_application_kyc_snapshot_age CHECK (
        kyc_snapshot_age IS NULL OR kyc_snapshot_age BETWEEN 0 AND 120
    ),
    ADD CONSTRAINT ck_loan_application_kyc_snapshot_income CHECK (
        kyc_snapshot_income_status IS NULL
        OR kyc_snapshot_income_status IN ('VERIFIED', 'SOURCE_VERIFIED', 'NOT_VERIFIED')
    ),
    ADD CONSTRAINT ck_loan_application_kyc_snapshot_source CHECK (
        kyc_snapshot_source IS NULL OR kyc_snapshot_source IN ('MOCK_USER_PROFILE', 'USER_SERVICE')
    );
