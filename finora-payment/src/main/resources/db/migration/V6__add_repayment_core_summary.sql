ALTER TABLE payment_repayments
    ADD COLUMN outstanding_interest NUMERIC(18,2),
    ADD COLUMN outstanding_fee NUMERIC(18,2),
    ADD COLUMN outstanding_penalty NUMERIC(18,2),
    ADD COLUMN total_outstanding NUMERIC(18,2),
    ADD COLUMN overdue_amount NUMERIC(18,2);

ALTER TABLE payment_repayments
    ADD CONSTRAINT ck_payment_repayments_core_summary CHECK (
        (outstanding_principal IS NULL
            AND outstanding_interest IS NULL
            AND outstanding_fee IS NULL
            AND outstanding_penalty IS NULL
            AND total_outstanding IS NULL
            AND overdue_amount IS NULL)
        OR (outstanding_principal >= 0
            AND outstanding_interest >= 0
            AND outstanding_fee >= 0
            AND outstanding_penalty >= 0
            AND total_outstanding >= 0
            AND overdue_amount >= 0)
    );
