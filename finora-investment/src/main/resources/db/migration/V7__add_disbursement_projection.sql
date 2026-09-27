ALTER TABLE market_listings
    ADD COLUMN disbursement_saga_id UUID,
    ADD COLUMN payment_reference VARCHAR(100),
    ADD COLUMN fineract_loan_id BIGINT,
    ADD COLUMN disbursed_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_market_listings_disbursement_saga
    ON market_listings (disbursement_saga_id) WHERE disbursement_saga_id IS NOT NULL;

