CREATE TABLE pending_scheduled_repayments (
    loan_application_id BIGINT PRIMARY KEY,
    repayment_id UUID NOT NULL UNIQUE,
    borrower_id VARCHAR(100) NOT NULL,
    due_date DATE NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_pending_scheduled_repayment_due
    ON pending_scheduled_repayments (due_date);
