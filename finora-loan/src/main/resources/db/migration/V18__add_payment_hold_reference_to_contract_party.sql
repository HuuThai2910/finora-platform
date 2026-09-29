ALTER TABLE loan_contract_parties
    ADD COLUMN payment_hold_reference VARCHAR(100);

CREATE UNIQUE INDEX uq_loan_contract_party_payment_hold
    ON loan_contract_parties (payment_hold_reference)
    WHERE payment_hold_reference IS NOT NULL;
