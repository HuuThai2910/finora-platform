-- W3D (R6): bốn mốc proof, receipt Fabric thật, explorer theo khoản vay và đối chiếu DB ↔ chain.

ALTER TABLE blockchain_proof_submissions
    ADD COLUMN source_event_type VARCHAR(80),
    ADD COLUMN source_event_version INTEGER,
    ADD COLUMN source_occurred_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN loan_application_id BIGINT,
    ADD COLUMN chain_check_status VARCHAR(30),
    ADD COLUMN chain_checked_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE blockchain_proof_submissions
    ADD CONSTRAINT ck_blockchain_proof_submissions_proof_type
        CHECK (proof_type IN ('CONTRACT_DOCUMENT', 'DISBURSEMENT', 'REPAYMENT', 'NOTE_OWNERSHIP')),
    ADD CONSTRAINT ck_blockchain_proof_submissions_source_event_version
        CHECK (source_event_version IS NULL OR source_event_version > 0),
    ADD CONSTRAINT ck_blockchain_proof_submissions_loan_application
        CHECK (loan_application_id IS NULL OR loan_application_id > 0),
    -- B50: receipt MOCK và receipt Fabric có định dạng loại trừ nhau, không bao giờ nằm nhầm provider.
    ADD CONSTRAINT ck_blockchain_proof_submissions_receipt_format
        CHECK (
            provider_transaction_id IS NULL
            OR (provider = 'MOCK'
                AND provider_transaction_id LIKE 'MOCK-%'
                AND provider_block_reference = 'MOCK-BLOCK-NOT-ON-CHAIN')
            OR (provider = 'HYPERLEDGER_FABRIC'
                AND provider_transaction_id ~ '^[0-9a-f]{64}$'
                AND (provider_block_reference IS NULL
                    OR provider_block_reference ~ '^[a-z0-9][a-z0-9.-]{0,99}/[0-9]{1,19}$'))
        ),
    -- Đối chiếu chain chỉ có nghĩa với proof đã ghi Fabric.
    ADD CONSTRAINT ck_blockchain_proof_submissions_chain_check
        CHECK (
            (chain_check_status IS NULL AND chain_checked_at IS NULL)
            OR (chain_check_status IN ('MATCHED', 'MISMATCHED', 'MISSING_ON_CHAIN')
                AND chain_checked_at IS NOT NULL
                AND provider = 'HYPERLEDGER_FABRIC'
                AND status = 'CONFIRMED')
        );

-- B53: một mốc nghiệp vụ chỉ có một proof logic. Bản ghi REPAYMENT version 1 (trước W3D) không nằm trong index;
-- service tra chúng theo cùng ba cột trước khi đăng ký proof mới nên vẫn không tạo bản thứ hai.
CREATE UNIQUE INDEX uq_blockchain_proof_submissions_milestone
    ON blockchain_proof_submissions (proof_type, aggregate_type, aggregate_id)
    WHERE payload_version >= 2;

-- Explorer: mọi proof của một khoản vay theo thứ tự thời gian.
CREATE INDEX idx_blockchain_proof_submissions_loan
    ON blockchain_proof_submissions (loan_application_id, created_at, id)
    WHERE loan_application_id IS NOT NULL;

-- Màn vận hành: proof đã vào DEAD_LETTER, mới nhất trước.
CREATE INDEX idx_blockchain_proof_submissions_dead_letter
    ON blockchain_proof_submissions (updated_at DESC, id DESC)
    WHERE status = 'DEAD_LETTER';

-- Worker đối chiếu: proof Fabric đã xác nhận, chưa kiểm hoặc kiểm lâu nhất trước.
CREATE INDEX idx_blockchain_proof_submissions_chain_check
    ON blockchain_proof_submissions (chain_checked_at NULLS FIRST, id)
    WHERE status = 'CONFIRMED' AND provider = 'HYPERLEDGER_FABRIC';
