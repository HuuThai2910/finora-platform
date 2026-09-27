-- Mỗi lender có một giao dịch SmartCA riêng trên cùng PDF/hash của Contract.
ALTER TABLE loan_contract_parties
    ADD COLUMN signature_document_id VARCHAR(100),
    ADD COLUMN signature_requested_at TIMESTAMPTZ;

-- Borrower party của hợp đồng nhiều bên đã ký SmartCA trước V16 lấy lại metadata
-- từ LoanContract (nguồn trạng thái async của borrower ở phiên bản cũ).
UPDATE loan_contract_parties party
SET signature_document_id = contract.signature_document_id,
    signature_requested_at = contract.signature_requested_at
FROM loan_contracts contract
WHERE party.contract_id = contract.id
  AND party.party_type = 'BORROWER'
  AND party.status = 'SIGNED'
  AND party.signature_provider = 'VNPT_SMART_CA'
  AND party.signature_document_id IS NULL;

ALTER TABLE loan_contract_parties DROP CONSTRAINT ck_contract_party_status;
ALTER TABLE loan_contract_parties ADD CONSTRAINT ck_contract_party_status CHECK (
    status IN ('PENDING_SIGNATURE', 'SIGNING', 'SIGNED', 'DECLINED', 'EXPIRED')
);

ALTER TABLE loan_contract_parties DROP CONSTRAINT ck_contract_party_signature;
ALTER TABLE loan_contract_parties ADD CONSTRAINT ck_contract_party_signature CHECK (
    (
        status = 'PENDING_SIGNATURE'
        AND signature_method IS NULL AND signature_provider IS NULL
        AND signature_transaction_id IS NULL AND signature_document_id IS NULL
        AND signature_requested_at IS NULL AND signature_evidence_hash IS NULL
        AND idempotency_key IS NULL AND request_hash IS NULL AND signed_at IS NULL
    )
    OR
    (
        status = 'SIGNING'
        AND signature_method = 'VNPT_SMART_CA' AND signature_provider = 'VNPT_SMART_CA'
        AND signature_transaction_id IS NOT NULL AND signature_document_id IS NOT NULL
        AND signature_requested_at IS NOT NULL AND signature_evidence_hash IS NULL
        AND idempotency_key IS NOT NULL AND length(request_hash) = 64 AND signed_at IS NULL
    )
    OR
    (
        status = 'SIGNED'
        AND signature_method IS NOT NULL AND signature_provider IS NOT NULL
        AND signature_transaction_id IS NOT NULL AND length(signature_evidence_hash) = 64
        AND idempotency_key IS NOT NULL AND length(request_hash) = 64 AND signed_at IS NOT NULL
        AND (
            (signature_provider = 'MOCK' AND signature_method = 'CLICK_WRAP_MVP'
                AND signature_document_id IS NULL AND signature_requested_at IS NULL)
            OR
            (signature_provider = 'VNPT_SMART_CA' AND signature_method = 'VNPT_SMART_CA'
                AND signature_document_id IS NOT NULL AND signature_requested_at IS NOT NULL)
        )
    )
    OR status IN ('DECLINED', 'EXPIRED')
);
