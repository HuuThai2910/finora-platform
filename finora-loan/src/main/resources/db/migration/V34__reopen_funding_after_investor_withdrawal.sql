-- FUNDING-REOPEN-001: một nhà đầu tư từ chối ký (hoặc hết hạn ký phần của mình) không còn hủy cả hợp đồng.
-- Hợp đồng nhiều bên chuyển PENDING_REFUNDING trong lúc khoản vay quay lại sàn gọi phần vốn còn thiếu; khi đủ vốn
-- lại, Loan phát hành lại văn bản (allocation mới) và hợp đồng quay về PENDING_LENDER_SIGNATURES.

ALTER TABLE loan_contracts DROP CONSTRAINT ck_loan_contract_status;
ALTER TABLE loan_contracts ADD CONSTRAINT ck_loan_contract_status CHECK (status IN (
    'PENDING_LENDER_SIGNATURES', 'PENDING_REFUNDING', 'PENDING_BORROWER_SIGNATURE',
    'PENDING_SIGNATURE', 'SIGNING', 'SIGNED', 'DECLINED', 'EXPIRED', 'EFFECTIVE', 'COMPLETED'
));

ALTER TABLE loan_contract_status_histories DROP CONSTRAINT ck_contract_history_from_status;
ALTER TABLE loan_contract_status_histories ADD CONSTRAINT ck_contract_history_from_status CHECK (
    from_status IS NULL OR from_status IN (
        'PENDING_LENDER_SIGNATURES', 'PENDING_REFUNDING', 'PENDING_BORROWER_SIGNATURE',
        'PENDING_SIGNATURE', 'SIGNING', 'SIGNED', 'DECLINED', 'EXPIRED', 'EFFECTIVE', 'COMPLETED'
    )
);

ALTER TABLE loan_contract_status_histories DROP CONSTRAINT ck_contract_history_to_status;
ALTER TABLE loan_contract_status_histories ADD CONSTRAINT ck_contract_history_to_status CHECK (to_status IN (
    'PENDING_LENDER_SIGNATURES', 'PENDING_REFUNDING', 'PENDING_BORROWER_SIGNATURE',
    'PENDING_SIGNATURE', 'SIGNING', 'SIGNED', 'DECLINED', 'EXPIRED', 'EFFECTIVE', 'COMPLETED'
));

-- PENDING_REFUNDING chưa có consent của người vay: lời từ chối của nhà đầu tư nằm ở dòng loan_contract_parties
-- (DECLINED + idempotency key) và history, không chiếm các cột consent cấp hợp đồng.
ALTER TABLE loan_contracts DROP CONSTRAINT ck_loan_contract_consent;
ALTER TABLE loan_contracts ADD CONSTRAINT ck_loan_contract_consent CHECK (
    (
        status IN ('PENDING_LENDER_SIGNATURES', 'PENDING_REFUNDING', 'PENDING_BORROWER_SIGNATURE',
                   'PENDING_SIGNATURE', 'EXPIRED')
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

-- Thời điểm nhà đầu tư từ chối/hết hạn phần vốn: dùng cho lịch sử xử lý; dòng cũ để NULL.
ALTER TABLE loan_contract_parties
    ADD COLUMN closed_at TIMESTAMPTZ;

-- SUPERSEDED: chữ ký đã nhúng vào văn bản trước khi phát hành lại. Giữ nguyên bytes/hash làm bằng chứng chấp thuận,
-- nhưng không còn thuộc chuỗi PDF hiện hành (các index một-bản-SIGNED chỉ xét status = 'SIGNED').
ALTER TABLE loan_contract_signatures DROP CONSTRAINT ck_loan_contract_signature_status;
ALTER TABLE loan_contract_signatures ADD CONSTRAINT ck_loan_contract_signature_status CHECK (
    status IN ('PREPARED', 'SIGNED', 'ABANDONED', 'SUPERSEDED')
);

ALTER TABLE loan_contract_signatures DROP CONSTRAINT ck_loan_contract_signature_lifecycle;
ALTER TABLE loan_contract_signatures ADD CONSTRAINT ck_loan_contract_signature_lifecycle CHECK (
    (status = 'PREPARED' AND signed_at IS NULL AND abandoned_at IS NULL AND abandon_reason IS NULL
        AND cms_hash IS NULL AND evidence_hash IS NULL)
    OR
    (status = 'SIGNED' AND signed_at IS NOT NULL AND abandoned_at IS NULL AND abandon_reason IS NULL
        AND length(cms_hash) = 64 AND length(evidence_hash) = 64)
    OR
    (status = 'ABANDONED' AND signed_at IS NULL AND abandoned_at IS NOT NULL
        AND abandon_reason IN (
            'PROVIDER_REJECTED', 'PROVIDER_SIGNATURE_INVALID', 'SIGNING_SESSION_EXPIRED', 'CONTRACT_CLOSED'
        )
        AND cms_hash IS NULL AND evidence_hash IS NULL)
    OR
    (status = 'SUPERSEDED' AND signed_at IS NOT NULL AND abandoned_at IS NOT NULL
        AND abandon_reason = 'DOCUMENT_REISSUED'
        AND length(cms_hash) = 64 AND length(evidence_hash) = 64)
);
