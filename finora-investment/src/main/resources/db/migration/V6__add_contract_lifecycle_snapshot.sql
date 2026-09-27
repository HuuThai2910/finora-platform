-- Investment chỉ giữ projection tối thiểu phục vụ UI; Loan vẫn là source of truth của Contract.
ALTER TABLE market_listings
    ADD COLUMN contract_status VARCHAR(40),
    ADD COLUMN contract_document_hash VARCHAR(64),
    ADD COLUMN contract_receipt_hash VARCHAR(64),
    ADD COLUMN contract_activated_at TIMESTAMPTZ;

ALTER TABLE market_listings
    ADD CONSTRAINT ck_market_listing_contract_status CHECK (
        contract_status IS NULL OR contract_status IN (
            'PENDING_LENDER_SIGNATURES', 'PENDING_BORROWER_SIGNATURE', 'EFFECTIVE'
        )
    ),
    ADD CONSTRAINT ck_market_listing_contract_hashes CHECK (
        (contract_document_hash IS NULL OR length(contract_document_hash) = 64)
        AND (contract_receipt_hash IS NULL OR length(contract_receipt_hash) = 64)
    );

CREATE UNIQUE INDEX uq_market_listing_contract_number
    ON market_listings (contract_number) WHERE contract_number IS NOT NULL;
