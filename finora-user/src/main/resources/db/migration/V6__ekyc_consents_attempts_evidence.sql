-- =====================================================================
-- V6 (EKYC-002): điều khoản đồng ý eKYC, lượt xác minh, bằng chứng và nhật ký xem bằng chứng.
-- Ảnh/video nằm trên kho S3 (SeaweedFS khi dev local, Amazon S3 khi deploy); bảng chỉ giữ
-- object key không chứa PII, SHA-256 và trạng thái lưu. Bản nháp duyệt tay mã hoá AES-GCM
-- ở tầng ứng dụng (CryptoConverter) trước khi ghi vào draft_encrypted.
-- =====================================================================

ALTER TABLE user_profiles
    ADD COLUMN id_card_type             VARCHAR(30),
    ADD COLUMN id_issue_date            DATE,
    ADD COLUMN id_expiry_date           DATE,
    ADD COLUMN birth_registration_place VARCHAR(500);

CREATE TABLE ekyc_consents (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_profile_id  BIGINT       NOT NULL,
    version          VARCHAR(40)  NOT NULL,
    purposes         VARCHAR(200) NOT NULL,           -- mã mục đích, phân tách dấu phẩy
    text_sha256      VARCHAR(64)  NOT NULL,
    platform         VARCHAR(20),
    app_version      VARCHAR(40),
    accepted_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    withdrawn_at     TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ekyc_consents_profile FOREIGN KEY (user_profile_id) REFERENCES user_profiles (id)
);
-- Tìm đồng ý mới nhất của một người dùng (kiểm tra đồng ý còn hiệu lực ở mọi bước eKYC).
CREATE INDEX idx_ekyc_consents_user_profile_id_accepted_at ON ekyc_consents (user_profile_id, accepted_at DESC);

CREATE TABLE ekyc_attempts (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id             UUID         NOT NULL,
    user_profile_id       BIGINT       NOT NULL,
    consent_id            BIGINT,
    status                VARCHAR(20)  NOT NULL,
    card_type             VARCHAR(30),
    document_decision     VARCHAR(20)  NOT NULL,
    document_reasons      TEXT,                       -- JSON mảng mã, không PII
    liveness_result       VARCHAR(10),
    liveness_reasons      TEXT,
    face_match_score      DOUBLE PRECISION,
    thresholds_version    VARCHAR(80),
    draft_encrypted       TEXT,                       -- JSON bản nháp, AES-GCM qua CryptoConverter
    submitted_at          TIMESTAMP WITH TIME ZONE,
    decided_at            TIMESTAMP WITH TIME ZONE,
    reviewer_keycloak_id  UUID,
    review_note           VARCHAR(1000),
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ekyc_attempts_public_id UNIQUE (public_id),
    CONSTRAINT fk_ekyc_attempts_profile FOREIGN KEY (user_profile_id) REFERENCES user_profiles (id),
    CONSTRAINT fk_ekyc_attempts_consent FOREIGN KEY (consent_id) REFERENCES ekyc_consents (id),
    CONSTRAINT ck_ekyc_attempts_status CHECK (status IN ('IN_PROGRESS','VERIFIED','MANUAL_REVIEW','APPROVED','REJECTED','ABANDONED')),
    CONSTRAINT ck_ekyc_attempts_document_decision CHECK (document_decision IN ('AUTO_OK','NEEDS_REVIEW')),
    CONSTRAINT ck_ekyc_attempts_liveness_result CHECK (liveness_result IS NULL OR liveness_result IN ('PASS','REVIEW','FAIL'))
);
-- Trang duyệt: lọc theo trạng thái, sắp theo thời điểm gửi.
CREATE INDEX idx_ekyc_attempts_status_submitted_at ON ekyc_attempts (status, submitted_at);
-- Huỷ lượt cũ khi quét lại, quét dọn lượt bỏ dở.
CREATE INDEX idx_ekyc_attempts_user_profile_id_created_at ON ekyc_attempts (user_profile_id, created_at DESC);

CREATE TABLE ekyc_evidence (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_id    BIGINT       NOT NULL,
    kind          VARCHAR(20)  NOT NULL,
    label         VARCHAR(30),
    object_key    VARCHAR(300) NOT NULL,
    sha256        VARCHAR(64)  NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    content_type  VARCHAR(60)  NOT NULL,
    stage         VARCHAR(10)  NOT NULL,              -- pending | final, khớp tag object
    deleted_at    TIMESTAMP WITH TIME ZONE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ekyc_evidence_object_key UNIQUE (object_key),
    CONSTRAINT fk_ekyc_evidence_attempt FOREIGN KEY (attempt_id) REFERENCES ekyc_attempts (id),
    CONSTRAINT ck_ekyc_evidence_kind CHECK (kind IN ('CCCD_FRONT','CCCD_BACK','LIVENESS_VIDEO','LIVENESS_FRAME','RESULT')),
    CONSTRAINT ck_ekyc_evidence_stage CHECK (stage IN ('pending','final'))
);
CREATE INDEX idx_ekyc_evidence_attempt_id ON ekyc_evidence (attempt_id);
-- Quét dọn theo hạn lưu.
CREATE INDEX idx_ekyc_evidence_stage_created_at ON ekyc_evidence (stage, created_at) WHERE deleted_at IS NULL;

CREATE TABLE ekyc_evidence_access_log (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_id         BIGINT NOT NULL,
    evidence_id        BIGINT,
    admin_keycloak_id  UUID   NOT NULL,
    accessed_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ekyc_access_attempt FOREIGN KEY (attempt_id) REFERENCES ekyc_attempts (id),
    CONSTRAINT fk_ekyc_access_evidence FOREIGN KEY (evidence_id) REFERENCES ekyc_evidence (id)
);
CREATE INDEX idx_ekyc_evidence_access_log_attempt_id ON ekyc_evidence_access_log (attempt_id, accessed_at);
