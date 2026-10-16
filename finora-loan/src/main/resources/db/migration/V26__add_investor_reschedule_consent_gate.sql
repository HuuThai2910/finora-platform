ALTER TABLE loan_reschedule_requests
    DROP CONSTRAINT ck_loan_reschedule_status;

ALTER TABLE loan_reschedule_requests
    ADD CONSTRAINT ck_loan_reschedule_status CHECK (status IN (
        'PENDING_REVIEW', 'PENDING_INVESTOR_CONSENT', 'INVESTOR_REJECTED', 'REJECTED',
        'CREATE_PENDING', 'CREATING', 'APPROVAL_PENDING', 'APPROVING', 'COMPLETED',
        'RECONCILIATION_REQUIRED', 'MANUAL_REVIEW'
    ));

DROP INDEX uq_loan_reschedule_one_active_per_loan;

CREATE UNIQUE INDEX uq_loan_reschedule_one_active_per_loan
    ON loan_reschedule_requests (finora_loan_id)
    WHERE status IN (
        'PENDING_REVIEW', 'PENDING_INVESTOR_CONSENT', 'CREATE_PENDING', 'CREATING',
        'APPROVAL_PENDING', 'APPROVING', 'RECONCILIATION_REQUIRED', 'MANUAL_REVIEW'
    );
