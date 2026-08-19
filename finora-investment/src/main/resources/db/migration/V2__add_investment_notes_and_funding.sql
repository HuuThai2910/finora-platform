-- Investment Notes + Funding progress

CREATE TABLE investment_notes (
    id              BIGSERIAL       PRIMARY KEY,
    listing_id      BIGINT          NOT NULL REFERENCES loan_listings(id),
    match_id        BIGINT          NOT NULL REFERENCES match_results(id),
    investor_id     BIGINT          NOT NULL,
    principal_amount NUMERIC(15,2)  NOT NULL CHECK (principal_amount > 0),
    purchase_date   TIMESTAMPTZ     NOT NULL DEFAULT now(),
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',

    CONSTRAINT chk_note_status CHECK (status IN ('ACTIVE','SOLD','COMPLETED','DEFAULTED'))
);

CREATE INDEX idx_note_investor ON investment_notes (investor_id);
CREATE INDEX idx_note_listing  ON investment_notes (listing_id);

-- Thêm cột funding progress vào loan_listings
ALTER TABLE loan_listings ADD COLUMN funded_amount  NUMERIC(15,2) NOT NULL DEFAULT 0;
ALTER TABLE loan_listings ADD COLUMN investor_count INT           NOT NULL DEFAULT 0;
