-- Quyết định duyệt hồ sơ và phản hồi điều khoản của borrower là hai trục độc lập.
-- Không tạo bảng offer và không sao chép pricing/schedule: application tiếp tục tham chiếu
-- submission_calculation_snapshot_id và final_calculation_snapshot_id làm nguồn sự thật.
ALTER TABLE loan_applications
    ADD COLUMN terms_confirmation_status VARCHAR(30),
    ADD COLUMN terms_version VARCHAR(50),
    ADD COLUMN terms_hash VARCHAR(64),
    ADD COLUMN terms_expires_at TIMESTAMPTZ,
    ADD COLUMN terms_responded_by VARCHAR(100),
    ADD COLUMN terms_responded_at TIMESTAMPTZ,
    ADD COLUMN terms_decline_reason_code VARCHAR(50),
    ADD COLUMN terms_decline_reason_detail VARCHAR(1000),
    ADD COLUMN terms_consent_idempotency_key VARCHAR(150),
    ADD COLUMN terms_consent_request_hash VARCHAR(64);

-- Dữ liệu legacy đã có Contract được giữ nguyên và đánh dấu là đã tiếp tục theo cơ chế cũ.
-- Dữ liệu APPROVED chưa có Contract buộc hết hạn để không tự suy diễn sự đồng ý của borrower.
UPDATE loan_applications application
SET terms_confirmation_status = CASE
        WHEN EXISTS (SELECT 1 FROM loan_contracts contract WHERE contract.application_id = application.id)
            THEN 'AUTO_AUTHORIZED'
        ELSE 'EXPIRED'
    END,
    terms_version = 'LEGACY_TERMS_V1',
    terms_hash = repeat('0', 64),
    terms_expires_at = COALESCE(application.updated_at, now()),
    terms_responded_by = CASE
        WHEN EXISTS (SELECT 1 FROM loan_contracts contract WHERE contract.application_id = application.id)
            THEN application.borrower_id
        ELSE NULL
    END,
    terms_responded_at = CASE
        WHEN EXISTS (SELECT 1 FROM loan_contracts contract WHERE contract.application_id = application.id)
            THEN application.pricing_disclosure_accepted_at
        ELSE NULL
    END
WHERE application.status = 'APPROVED';

ALTER TABLE loan_applications
    ADD CONSTRAINT ck_loan_application_terms_status CHECK (
        terms_confirmation_status IS NULL OR terms_confirmation_status IN (
            'AUTO_AUTHORIZED', 'PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED'
        )
    ),
    ADD CONSTRAINT ck_loan_application_terms_evidence CHECK (
        (status <> 'APPROVED' AND terms_confirmation_status IS NULL)
        OR
        (terms_confirmation_status IS NOT NULL
            AND terms_version IS NOT NULL
            AND length(terms_hash) = 64
            AND terms_expires_at IS NOT NULL)
    ),
    ADD CONSTRAINT ck_loan_application_terms_response CHECK (
        terms_confirmation_status NOT IN ('AUTO_AUTHORIZED', 'ACCEPTED', 'DECLINED')
        OR (terms_responded_by IS NOT NULL AND terms_responded_at IS NOT NULL)
    ),
    ADD CONSTRAINT ck_loan_application_terms_consent_hash CHECK (
        (terms_consent_idempotency_key IS NULL AND terms_consent_request_hash IS NULL)
        OR (terms_consent_idempotency_key IS NOT NULL AND length(terms_consent_request_hash) = 64)
    );

CREATE UNIQUE INDEX uq_loan_application_terms_consent_key
    ON loan_applications (terms_consent_idempotency_key)
    WHERE terms_consent_idempotency_key IS NOT NULL;

CREATE INDEX idx_loan_application_pending_terms_expiry
    ON loan_applications (terms_expires_at, id)
    WHERE terms_confirmation_status = 'PENDING';
