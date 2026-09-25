ALTER TABLE loan_contracts
    ADD COLUMN signature_document_id VARCHAR(100),
    ADD COLUMN signature_requested_at TIMESTAMPTZ;

ALTER TABLE loan_contracts DROP CONSTRAINT ck_loan_contract_status;
ALTER TABLE loan_contracts ADD CONSTRAINT ck_loan_contract_status CHECK (status IN (
    'PENDING_SIGNATURE', 'SIGNING', 'SIGNED', 'DECLINED', 'EXPIRED', 'EFFECTIVE', 'COMPLETED'
));

ALTER TABLE loan_contract_status_histories DROP CONSTRAINT ck_contract_history_from_status;
ALTER TABLE loan_contract_status_histories ADD CONSTRAINT ck_contract_history_from_status CHECK (
    from_status IS NULL OR from_status IN (
        'PENDING_SIGNATURE', 'SIGNING', 'SIGNED', 'DECLINED', 'EXPIRED', 'EFFECTIVE', 'COMPLETED'
    )
);

ALTER TABLE loan_contract_status_histories DROP CONSTRAINT ck_contract_history_to_status;
ALTER TABLE loan_contract_status_histories ADD CONSTRAINT ck_contract_history_to_status CHECK (to_status IN (
    'PENDING_SIGNATURE', 'SIGNING', 'SIGNED', 'DECLINED', 'EXPIRED', 'EFFECTIVE', 'COMPLETED'
));

ALTER TABLE loan_contracts DROP CONSTRAINT ck_loan_contract_consent;
ALTER TABLE loan_contracts ADD CONSTRAINT ck_loan_contract_consent CHECK (
    (
        status IN ('PENDING_SIGNATURE', 'EXPIRED')
        AND consent_idempotency_key IS NULL AND consent_request_hash IS NULL AND consent_action IS NULL
        AND signed_by IS NULL AND signed_at IS NULL AND signature_method IS NULL
        AND signature_provider IS NULL AND signature_transaction_id IS NULL
        AND signature_document_id IS NULL AND signature_requested_at IS NULL
        AND signature_evidence_hash IS NULL
        AND declined_by IS NULL AND declined_at IS NULL AND decline_reason_code IS NULL
    )
    OR
    (
        status = 'SIGNING'
        AND consent_idempotency_key IS NOT NULL AND consent_request_hash IS NOT NULL
        AND consent_action = 'SIGN'
        AND signed_by IS NULL AND signed_at IS NULL
        AND signature_method = 'VNPT_SMART_CA' AND signature_provider = 'VNPT_SMART_CA'
        AND signature_transaction_id IS NOT NULL AND signature_document_id IS NOT NULL
        AND signature_requested_at IS NOT NULL AND signature_evidence_hash IS NULL
        AND declined_by IS NULL AND declined_at IS NULL AND decline_reason_code IS NULL
    )
    OR
    (
        status IN ('SIGNED', 'EFFECTIVE', 'COMPLETED')
        AND consent_idempotency_key IS NOT NULL AND consent_request_hash IS NOT NULL
        AND consent_action = 'SIGN'
        AND signed_by IS NOT NULL AND signed_at IS NOT NULL
        AND signature_method IN ('CLICK_WRAP_MVP', 'VNPT_SMART_CA')
        AND signature_provider IN ('MOCK', 'VNPT_SMART_CA')
        AND signature_transaction_id IS NOT NULL AND length(signature_evidence_hash) = 64
        AND (
            (signature_provider = 'MOCK' AND signature_method = 'CLICK_WRAP_MVP')
            OR (
                signature_provider = 'VNPT_SMART_CA' AND signature_method = 'VNPT_SMART_CA'
                AND signature_document_id IS NOT NULL AND signature_requested_at IS NOT NULL
            )
        )
        AND declined_by IS NULL AND declined_at IS NULL AND decline_reason_code IS NULL
    )
    OR
    (
        status = 'DECLINED'
        AND consent_idempotency_key IS NOT NULL AND consent_request_hash IS NOT NULL
        AND consent_action = 'DECLINE'
        AND signed_by IS NULL AND signed_at IS NULL AND signature_method IS NULL
        AND signature_provider IS NULL AND signature_transaction_id IS NULL
        AND signature_document_id IS NULL AND signature_requested_at IS NULL
        AND signature_evidence_hash IS NULL
        AND declined_by IS NOT NULL AND declined_at IS NOT NULL AND decline_reason_code IS NOT NULL
    )
);

DROP INDEX idx_loan_contracts_pending_expiry;
CREATE INDEX idx_loan_contracts_pending_expiry
    ON loan_contracts (expires_at, id) WHERE status IN ('PENDING_SIGNATURE', 'SIGNING');

CREATE INDEX idx_loan_contracts_signing_requested
    ON loan_contracts (signature_requested_at, id) WHERE status = 'SIGNING';
