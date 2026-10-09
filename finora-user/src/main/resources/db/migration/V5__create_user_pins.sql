-- Mã PIN giao dịch — xác nhận thao tác nhạy cảm (rút tiền, trả nợ, đầu tư, đặt lệnh,
-- Auto-Invest, ký hợp đồng). Chỉ lưu bcrypt hash; PIN gốc không bao giờ rời request.
-- Khoá theo keycloak_user_id vì đó là định danh có sẵn trong mọi access token.
CREATE TABLE user_pins (
    keycloak_user_id    UUID                    PRIMARY KEY,
    pin_hash            VARCHAR(100)            NOT NULL,
    failed_attempts     INTEGER                 NOT NULL DEFAULT 0,
    locked_until        TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_user_pins_profile
        FOREIGN KEY (keycloak_user_id) REFERENCES user_profiles (keycloak_user_id),
    CONSTRAINT ck_user_pins_failed_attempts CHECK (failed_attempts >= 0)
);
