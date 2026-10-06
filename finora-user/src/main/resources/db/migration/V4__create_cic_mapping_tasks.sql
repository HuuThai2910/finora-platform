-- Đăng ký ánh xạ định danh sang CIC theo cơ chế retry bền vững.
-- Bảng chỉ giữ khóa profile; CCCD vẫn chỉ tồn tại ở user_profiles dưới dạng mã hóa.
CREATE TABLE user_cic_mapping_tasks (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_profile_id     BIGINT                  NOT NULL,
    status              VARCHAR(20)             NOT NULL,
    attempt_count       INTEGER                 NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error_code     VARCHAR(80),
    completed_at        TIMESTAMP WITH TIME ZONE,
    version             BIGINT                  NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_user_cic_mapping_profile
        FOREIGN KEY (user_profile_id) REFERENCES user_profiles (id),
    CONSTRAINT uq_user_cic_mapping_profile UNIQUE (user_profile_id),
    CONSTRAINT ck_user_cic_mapping_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY_PENDING', 'COMPLETED', 'DEAD')
    ),
    CONSTRAINT ck_user_cic_mapping_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_user_cic_mapping_completion CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL)
    )
);

CREATE INDEX idx_user_cic_mapping_due
    ON user_cic_mapping_tasks (next_attempt_at, id)
    WHERE status IN ('PENDING', 'PROCESSING', 'RETRY_PENDING');

-- Backfill các tài khoản đã eKYC trước khi tích hợp CIC được đưa vào hàng chờ.
INSERT INTO user_cic_mapping_tasks (
    user_profile_id, status, attempt_count, next_attempt_at, created_at, updated_at
)
SELECT id, 'PENDING', 0, NOW(), NOW(), NOW()
FROM user_profiles
WHERE ekyc_status = 'VERIFIED'
  AND id_number_encrypted IS NOT NULL;
