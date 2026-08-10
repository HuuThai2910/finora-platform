CREATE INDEX idx_loan_applications_admin_created
    ON loan_applications (created_at DESC, id DESC);

CREATE INDEX idx_loan_applications_admin_status_created
    ON loan_applications (status, created_at DESC, id DESC);
