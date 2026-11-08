ALTER TABLE loan_reschedule_requests
    ADD COLUMN previous_loan_status VARCHAR(30);

ALTER TABLE loan_reschedule_requests
    ADD CONSTRAINT ck_loan_reschedule_previous_loan_status
        CHECK (previous_loan_status IS NULL OR previous_loan_status IN ('ACTIVE', 'DEFAULTED'));
