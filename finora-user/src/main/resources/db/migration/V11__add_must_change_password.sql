-- =====================================================================
-- V11 (STAFF-ACCOUNT-001): tài khoản nhân viên do quản trị viên tạo nhận mật khẩu tạm; cờ này bắt người dùng đổi
-- mật khẩu ở lần đăng nhập đầu. Không dùng required action UPDATE_PASSWORD của Keycloak vì đăng nhập đi qua direct
-- grant (password grant) — required action làm Keycloak từ chối cấp token.
-- Tài khoản hiện có (khách tự đăng ký, nhân viên cũ) giữ FALSE.
-- =====================================================================
ALTER TABLE user_profiles
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
