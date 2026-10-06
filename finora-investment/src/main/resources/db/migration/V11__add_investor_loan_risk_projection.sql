ALTER TABLE investment_loan_servicing_states
    ADD COLUMN days_past_due INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN debt_group INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN overdue_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    ADD COLUMN total_outstanding NUMERIC(18, 2) NOT NULL DEFAULT 0,
    ADD COLUMN overdue_since DATE,
    ADD COLUMN risk_changed_at TIMESTAMPTZ,
    ADD COLUMN risk_data_as_of TIMESTAMPTZ;

ALTER TABLE investment_loan_servicing_states
    ADD CONSTRAINT ck_investment_servicing_dpd CHECK (days_past_due >= 0),
    ADD CONSTRAINT ck_investment_servicing_debt_group CHECK (debt_group BETWEEN 1 AND 5),
    ADD CONSTRAINT ck_investment_servicing_risk_money CHECK (
        overdue_amount >= 0 AND total_outstanding >= 0
    );

CREATE INDEX idx_investment_servicing_risk
    ON investment_loan_servicing_states (debt_group, days_past_due, updated_at);
