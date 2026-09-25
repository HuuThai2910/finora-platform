ALTER TABLE loan_contracts
    ADD COLUMN signature_provider VARCHAR(30),
    ADD COLUMN signature_transaction_id VARCHAR(150),
    ADD COLUMN signature_evidence_hash VARCHAR(64);

-- Các bản click-wrap đã ký trước V11 vẫn giữ nguyên ý nghĩa và được gắn nguồn legacy rõ ràng.
UPDATE loan_contracts
SET signature_provider = 'MOCK',
    signature_transaction_id = 'LEGACY-' || id,
    signature_evidence_hash = document_hash
WHERE status IN ('SIGNED', 'EFFECTIVE', 'COMPLETED');

ALTER TABLE loan_contracts DROP CONSTRAINT ck_loan_contract_consent;
ALTER TABLE loan_contracts ADD CONSTRAINT ck_loan_contract_consent CHECK (
    (
        status IN ('PENDING_SIGNATURE', 'EXPIRED')
        AND consent_idempotency_key IS NULL AND consent_request_hash IS NULL AND consent_action IS NULL
        AND signed_by IS NULL AND signed_at IS NULL AND signature_method IS NULL
        AND signature_provider IS NULL AND signature_transaction_id IS NULL AND signature_evidence_hash IS NULL
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
            OR (signature_provider = 'VNPT_SMART_CA' AND signature_method = 'VNPT_SMART_CA')
        )
        AND declined_by IS NULL AND declined_at IS NULL AND decline_reason_code IS NULL
    )
    OR
    (
        status = 'DECLINED'
        AND consent_idempotency_key IS NOT NULL AND consent_request_hash IS NOT NULL
        AND consent_action = 'DECLINE'
        AND signed_by IS NULL AND signed_at IS NULL AND signature_method IS NULL
        AND signature_provider IS NULL AND signature_transaction_id IS NULL AND signature_evidence_hash IS NULL
        AND declined_by IS NOT NULL AND declined_at IS NOT NULL AND decline_reason_code IS NOT NULL
    )
);

CREATE UNIQUE INDEX uq_loan_contracts_signature_provider_transaction
    ON loan_contracts (signature_provider, signature_transaction_id)
    WHERE signature_transaction_id IS NOT NULL;
