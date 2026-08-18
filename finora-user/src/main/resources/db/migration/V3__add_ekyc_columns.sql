ALTER TABLE user_profiles
    ADD COLUMN ekyc_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN face_match_score DOUBLE PRECISION,
    ADD COLUMN liveness_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN document_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN ekyc_completed_at TIMESTAMPTZ;

CREATE INDEX idx_user_profiles_ekyc_status ON user_profiles (ekyc_status);
