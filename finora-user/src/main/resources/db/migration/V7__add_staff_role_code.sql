-- =====================================================================
-- V7 (phân quyền nhân viên): tài khoản STAFF gắn đúng một vai trò nhân viên.
-- Vai trò nhân viên là realm role STAFF_* trên Keycloak (Keycloak là nguồn sự thật của quyền);
-- cột này chỉ giữ mã vai trò để đếm thành viên, chặn xoá vai trò đang dùng và hiển thị ở màn quản trị.
-- Chỉ có giá trị khi role = 'STAFF'.
-- =====================================================================

ALTER TABLE user_profiles
    ADD COLUMN staff_role_code VARCHAR(60);

ALTER TABLE user_profiles
    ADD CONSTRAINT ck_user_profiles_staff_role_code
        CHECK ((role = 'STAFF' AND staff_role_code IS NOT NULL)
            OR (role <> 'STAFF' AND staff_role_code IS NULL));

-- Đếm thành viên theo vai trò và tìm người cần đăng xuất khi vai trò đổi quyền.
CREATE INDEX idx_user_profiles_staff_role_code ON user_profiles (staff_role_code)
    WHERE staff_role_code IS NOT NULL;
