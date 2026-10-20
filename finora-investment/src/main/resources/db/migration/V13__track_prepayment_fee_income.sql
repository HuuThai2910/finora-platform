ALTER TABLE investment_notes
    ADD COLUMN prepayment_fee_received NUMERIC(18,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_investment_notes_prepayment_fee_received CHECK (prepayment_fee_received >= 0);
