-- =====================================================================
-- V1: Bảng user_profiles — hồ sơ người dùng FINORA
-- SoR: finora-user sở hữu profile; Keycloak sở hữu credential/session.
-- Cầu nối: keycloak_user_id (UUID) liên kết 1:1 với Keycloak User.
-- PII: số CCCD và SĐT lưu dạng HMAC-SHA256 hash (lookup/unique)
--       + AES-256-GCM encrypted (giải mã khi cần hiển thị cho chủ sở hữu).
-- =====================================================================

CREATE TABLE user_profiles (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Liên kết với Keycloak User — mỗi profile khớp đúng 1 tài khoản Keycloak
    keycloak_user_id    UUID            NOT NULL,
    email               VARCHAR(255)    NOT NULL,

    -- Thông tin cá nhân (từ NFC CCCD hoặc nhập tay)
    full_name           VARCHAR(255),
    date_of_birth       DATE,
    gender              VARCHAR(10),
    place_of_origin     VARCHAR(500),
    address             TEXT,

    -- Số CCCD — hash để tìm kiếm/kiểm tra trùng, encrypted để giải mã khi cần
    id_number_hash      CHAR(64),
    id_number_encrypted VARCHAR(500),

    -- Số điện thoại — cùng chiến lược hash + encrypt như CCCD
    phone_hash          CHAR(64),
    phone_encrypted     VARCHAR(500),

    -- Vai trò chính: BORROWER, INVESTOR, ADMIN (Keycloak là SoR cho role,
    -- cột này cache để query nhanh mà không gọi Keycloak)
    role                VARCHAR(20)     NOT NULL DEFAULT 'BORROWER',

    -- Đánh dấu hồ sơ đã đủ thông tin bắt buộc (tên, CCCD, SĐT)
    profile_completed   BOOLEAN         NOT NULL DEFAULT FALSE,

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- === Unique Constraints ===
-- Mỗi tài khoản Keycloak chỉ có 1 profile
ALTER TABLE user_profiles
    ADD CONSTRAINT uq_user_profiles_keycloak_user_id UNIQUE (keycloak_user_id);

-- Email duy nhất trong hệ thống (Keycloak cũng enforce, đây là lớp bảo vệ thứ hai)
ALTER TABLE user_profiles
    ADD CONSTRAINT uq_user_profiles_email UNIQUE (email);

-- Mỗi số CCCD chỉ được đăng ký 1 lần (so sánh qua HMAC hash)
ALTER TABLE user_profiles
    ADD CONSTRAINT uq_user_profiles_id_number_hash UNIQUE (id_number_hash);

-- Mỗi số điện thoại chỉ được đăng ký 1 lần
ALTER TABLE user_profiles
    ADD CONSTRAINT uq_user_profiles_phone_hash UNIQUE (phone_hash);

-- === Indexes ===
-- Admin phân trang danh sách người dùng: ORDER BY created_at DESC LIMIT/OFFSET
-- Quy mô đồ án nhỏ, index này đủ cho vài nghìn bản ghi
CREATE INDEX idx_user_profiles_created_at ON user_profiles (created_at DESC);
