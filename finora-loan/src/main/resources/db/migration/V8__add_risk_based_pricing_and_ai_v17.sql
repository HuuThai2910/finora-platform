-- Product giữ ba mức lãi suất. Cột annual_interest_rate cũ tiếp tục là base rate
-- để dữ liệu/API hiện hữu không phải đổi tên phá vỡ trong cùng một lần nâng cấp.
ALTER TABLE loan_products
    ADD COLUMN min_annual_interest_rate NUMERIC(7, 4),
    ADD COLUMN max_annual_interest_rate NUMERIC(7, 4);

UPDATE loan_products
SET min_annual_interest_rate = annual_interest_rate,
    max_annual_interest_rate = annual_interest_rate;

ALTER TABLE loan_products
    ALTER COLUMN min_annual_interest_rate SET NOT NULL,
    ALTER COLUMN max_annual_interest_rate SET NOT NULL,
    DROP CONSTRAINT ck_loan_product_rate,
    ADD CONSTRAINT ck_loan_product_rate CHECK (
        min_annual_interest_rate > 0
        AND min_annual_interest_rate <= annual_interest_rate
        AND annual_interest_rate <= max_annual_interest_rate
        AND max_annual_interest_rate <= 20.0000
    ),
    DROP CONSTRAINT ck_loan_product_term,
    ADD CONSTRAINT ck_loan_product_term CHECK (
        min_term_months > 0
        AND max_term_months >= min_term_months
        AND max_term_months <= 24
    );

-- Application chụp đủ biên Product và kết quả định giá để thay đổi cấu hình sau này
-- không làm sai điều khoản của hồ sơ đã nộp.
ALTER TABLE loan_applications
    ADD COLUMN min_annual_interest_rate_snapshot NUMERIC(7, 4),
    ADD COLUMN max_annual_interest_rate_snapshot NUMERIC(7, 4),
    ADD COLUMN final_annual_interest_rate NUMERIC(7, 4),
    ADD COLUMN pricing_credit_grade VARCHAR(8),
    ADD COLUMN pricing_adjustment_percentage_points NUMERIC(7, 4),
    ADD COLUMN pricing_policy_version VARCHAR(50),
    ADD COLUMN final_calculation_snapshot_id BIGINT,
    ADD COLUMN decision_source VARCHAR(20),
    ADD COLUMN automated_decision_policy_version VARCHAR(50),
    ADD COLUMN automated_decided_at TIMESTAMPTZ;

UPDATE loan_applications
SET min_annual_interest_rate_snapshot = annual_interest_rate_snapshot,
    max_annual_interest_rate_snapshot = annual_interest_rate_snapshot;

UPDATE loan_applications
SET decision_source = 'ADMIN'
WHERE admin_decided_at IS NOT NULL;

-- Hồ sơ APPROVED từ phiên bản fixed-rate đã có Contract hợp lệ: giữ nguyên điều khoản
-- và đánh dấu policy legacy thay vì buộc người dùng phải chấm/duyệt lại.
UPDATE loan_applications application
SET final_annual_interest_rate = contract.annual_interest_rate,
    pricing_credit_grade = COALESCE(
        (SELECT assessment.credit_grade
         FROM credit_scoring_assessments assessment
         WHERE assessment.id = application.latest_credit_assessment_id),
        'LEGACY'
    ),
    pricing_adjustment_percentage_points = 0.0000,
    pricing_policy_version = 'LEGACY_FIXED_RATE_V1',
    final_calculation_snapshot_id = contract.calculation_snapshot_id
FROM loan_contracts contract
WHERE contract.application_id = application.id
  AND application.status = 'APPROVED';

ALTER TABLE loan_applications
    ALTER COLUMN min_annual_interest_rate_snapshot SET NOT NULL,
    ALTER COLUMN max_annual_interest_rate_snapshot SET NOT NULL,
    ADD CONSTRAINT ck_loan_application_pricing CHECK (
        (
            final_annual_interest_rate IS NULL
            AND pricing_credit_grade IS NULL
            AND pricing_adjustment_percentage_points IS NULL
            AND pricing_policy_version IS NULL
        )
        OR
        (
            final_annual_interest_rate BETWEEN min_annual_interest_rate_snapshot
                                           AND max_annual_interest_rate_snapshot
            AND final_annual_interest_rate <= 20.0000
            AND pricing_credit_grade IS NOT NULL
            AND pricing_adjustment_percentage_points IS NOT NULL
            AND pricing_policy_version IS NOT NULL
        )
    ),
    ADD CONSTRAINT ck_loan_application_decision_source CHECK (
        decision_source IS NULL OR decision_source IN ('AI_POLICY', 'ADMIN')
    );

-- Một hồ sơ có hai snapshot: lịch ban đầu để AI/tham chiếu và lịch cuối để lập hợp đồng.
ALTER TABLE schedule_calculation_snapshots
    DROP CONSTRAINT schedule_calculation_snapshots_application_id_key,
    DROP CONSTRAINT ck_schedule_snapshot_purpose,
    ADD CONSTRAINT uq_schedule_snapshot_application_purpose UNIQUE (application_id, purpose),
    ADD CONSTRAINT ck_schedule_snapshot_purpose CHECK (purpose IN ('SUBMISSION_SCORING', 'CONTRACT'));

ALTER TABLE loan_applications
    ADD CONSTRAINT fk_loan_application_final_calculation
    FOREIGN KEY (final_calculation_snapshot_id) REFERENCES schedule_calculation_snapshots (id);

ALTER TABLE credit_scoring_assessments
    DROP CONSTRAINT ck_credit_assessment_grade,
    ADD CONSTRAINT ck_credit_assessment_grade CHECK (
        credit_grade IS NULL OR credit_grade ~ '^[A-Z][A-Z0-9+\-]{0,7}$'
    );

ALTER TABLE loan_applications
    DROP CONSTRAINT ck_loan_application_admin_decision_evidence,
    DROP CONSTRAINT ck_loan_application_approved_assessment;

ALTER TABLE loan_applications
    ADD CONSTRAINT ck_loan_application_admin_decision_evidence CHECK (
        decision_source <> 'ADMIN'
        OR (
            status IN ('APPROVED', 'REJECTED')
            AND admin_decision_reason_code IS NOT NULL
            AND admin_decision_policy_version IS NOT NULL
            AND admin_decision_idempotency_key IS NOT NULL
            AND admin_decision_request_hash IS NOT NULL
            AND admin_decided_by IS NOT NULL
            AND admin_decided_at IS NOT NULL
        )
    ),
    ADD CONSTRAINT ck_loan_application_automated_decision_evidence CHECK (
        decision_source <> 'AI_POLICY'
        OR (
            status IN ('APPROVED', 'REJECTED')
            AND latest_credit_assessment_id IS NOT NULL
            AND automated_decision_policy_version IS NOT NULL
            AND automated_decided_at IS NOT NULL
        )
    ),
    ADD CONSTRAINT ck_loan_application_approved_terms CHECK (
        status <> 'APPROVED'
        OR (
            latest_credit_assessment_id IS NOT NULL
            AND final_annual_interest_rate IS NOT NULL
            AND final_calculation_snapshot_id IS NOT NULL
        )
    );

ALTER TABLE loan_contracts
    DROP CONSTRAINT ck_loan_contract_rate,
    ADD CONSTRAINT ck_loan_contract_rate CHECK (
        annual_interest_rate > 0 AND annual_interest_rate <= 20.0000
    );

CREATE INDEX idx_loan_applications_ai_decision
    ON loan_applications (decision_source, automated_decided_at DESC, id DESC)
    WHERE decision_source = 'AI_POLICY';
