ALTER TABLE payment_repayments
    DROP CONSTRAINT ck_payment_repayments_type;

ALTER TABLE payment_repayments
    ADD CONSTRAINT ck_payment_repayments_type
        CHECK (repayment_type IN ('SCHEDULED','OVERDUE_CURE','EARLY_SETTLEMENT'));
