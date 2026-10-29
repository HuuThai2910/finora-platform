-- Mốc proof mới: giấy xác nhận chuyển nhượng Note (PDF ký PAdES) do Investment cấp khi giao dịch sổ lệnh tất toán.
-- Aggregate là NoteTransferDocument/<tradeReference>; unique index mốc ở V2 đã áp dụng chung theo proof_type.

ALTER TABLE blockchain_proof_submissions
    DROP CONSTRAINT ck_blockchain_proof_submissions_proof_type;

ALTER TABLE blockchain_proof_submissions
    ADD CONSTRAINT ck_blockchain_proof_submissions_proof_type
        CHECK (proof_type IN ('CONTRACT_DOCUMENT', 'DISBURSEMENT', 'REPAYMENT', 'NOTE_OWNERSHIP',
                              'NOTE_TRANSFER_DOCUMENT'));
