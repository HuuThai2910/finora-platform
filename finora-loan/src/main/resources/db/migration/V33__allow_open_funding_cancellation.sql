-- Borrower được rút sau duyệt khi listing còn OPEN. Khi đó Loan chưa nhận LoanFullyFunded nên chưa có
-- investment_listing_id/allocation; Investment tra listing bằng application_number + funding_round.
ALTER TABLE loan_applications
    DROP CONSTRAINT ck_loan_applications_funding_cancelled_complete;

ALTER TABLE loan_applications
    ADD CONSTRAINT ck_loan_applications_funding_cancelled_complete CHECK (
        funding_status <> 'CANCELLED'
        OR (
            funding_cancelled_at IS NOT NULL
            AND funding_cancel_reason IS NOT NULL
            AND (
                (funding_cancel_reason = 'BORROWER_WITHDREW'
                    AND allocation_hash IS NULL AND allocation_version IS NULL)
                OR
                (funding_cancel_reason <> 'BORROWER_WITHDREW'
                    AND investment_listing_id IS NOT NULL AND allocation_hash IS NOT NULL)
            )
        )
    );
