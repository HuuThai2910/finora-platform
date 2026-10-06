CREATE INDEX idx_loan_servicing_projection_stale_queue
    ON loan_servicing_projections (updated_at, id)
    WHERE stale = TRUE;
