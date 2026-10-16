ALTER TABLE payment_early_settlement_quotes
    ADD COLUMN investor_fee_share NUMERIC(18,2) NOT NULL DEFAULT 0,
    ADD COLUMN finora_fee_share NUMERIC(18,2) NOT NULL DEFAULT 0;

UPDATE payment_early_settlement_quotes
SET finora_fee_share = platform_fee;

ALTER TABLE payment_early_settlement_quotes
    ADD CONSTRAINT ck_payment_early_settlement_fee_split CHECK (
        investor_fee_share >= 0 AND finora_fee_share >= 0
        AND investor_fee_share + finora_fee_share = platform_fee
    );

ALTER TABLE payment_partial_prepayment_quotes
    ADD COLUMN investor_fee_share NUMERIC(18,2) NOT NULL DEFAULT 0,
    ADD COLUMN finora_fee_share NUMERIC(18,2) NOT NULL DEFAULT 0;

UPDATE payment_partial_prepayment_quotes
SET finora_fee_share = platform_fee;

ALTER TABLE payment_partial_prepayment_quotes
    ADD CONSTRAINT ck_payment_partial_prepayment_fee_split CHECK (
        investor_fee_share >= 0 AND finora_fee_share >= 0
        AND investor_fee_share + finora_fee_share = platform_fee
    );

ALTER TABLE payment_repayments
    ADD COLUMN investor_fee_share NUMERIC(18,2) NOT NULL DEFAULT 0,
    ADD COLUMN finora_fee_share NUMERIC(18,2) NOT NULL DEFAULT 0;

UPDATE payment_repayments
SET finora_fee_share = platform_fee;

ALTER TABLE payment_repayments
    ADD CONSTRAINT ck_payment_repayment_fee_split CHECK (
        investor_fee_share >= 0 AND finora_fee_share >= 0
        AND investor_fee_share + finora_fee_share = platform_fee
    );
