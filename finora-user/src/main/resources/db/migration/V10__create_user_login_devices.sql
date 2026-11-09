-- =====================================================================
-- V10 (LOGIN-DEVICE-001): thiết bị đã đăng nhập vào từng tài khoản, để người dùng xem "Thiết bị đăng nhập" và nhân viên
-- hỗ trợ xem ở trang khách hàng. Mỗi dòng là một thiết bị của một người dùng (khóa user + device_id do app/web tự sinh,
-- thiếu thì suy từ User-Agent). Ghi ở mọi lần đăng nhập thành công (đăng nhập, xác thực đăng ký), không ghi khi làm mới
-- token. Tên/HĐH/phiên bản là dữ liệu client tự khai, chỉ để hiển thị.
-- IP lưu nguyên như nhật ký hoạt động (cho nhân viên hỗ trợ); API của chính người dùng trả IP đã che.
-- keycloak_session_id là claim `sid` của access token lúc đăng nhập: dùng để đăng xuất đúng phiên của thiết bị.
-- =====================================================================
CREATE TABLE user_login_devices (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_profile_id     BIGINT                   NOT NULL REFERENCES user_profiles (id) ON DELETE CASCADE,
    device_id           VARCHAR(64)              NOT NULL,
    device_name         VARCHAR(100)             NOT NULL,
    os_name             VARCHAR(60),
    client_type         VARCHAR(10)              NOT NULL,
    app_version         VARCHAR(30),
    last_ip             VARCHAR(45),
    keycloak_session_id VARCHAR(64),
    first_login_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    login_count         INTEGER                  NOT NULL DEFAULT 1,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_user_login_devices_user_profile_id_device_id UNIQUE (user_profile_id, device_id),
    CONSTRAINT ck_user_login_devices_client_type CHECK (client_type IN ('MOBILE', 'WEB')),
    CONSTRAINT ck_user_login_devices_login_count CHECK (login_count >= 1)
);

CREATE INDEX idx_user_login_devices_user_profile_id_last_login_at
    ON user_login_devices (user_profile_id, last_login_at DESC);
