package com.finora.user.dto.response;

import com.finora.common.enums.user.UserRole;

/**
 * Kết quả tạo tài khoản nhân viên. Đây là lần DUY NHẤT mật khẩu tạm rời khỏi hệ thống qua API: không lưu ở đâu khác
 * ngoài Keycloak (dạng băm), không có API đọc lại; mất thì quản trị viên hướng dẫn nhân viên dùng "Quên mật khẩu".
 *
 * @param id                   ID hồ sơ ({@code user_profiles.id})
 * @param staffRoleCode        mã vai trò nhân viên; {@code null} với quản trị viên
 * @param staffRoleName        tên hiển thị của vai trò nhân viên; {@code null} với quản trị viên
 * @param temporaryPassword    mật khẩu tạm để quản trị viên giao cho nhân viên; bắt đổi ở lần đăng nhập đầu
 * @param credentialsEmailSent đã chuyển email thông tin đăng nhập sang dịch vụ thông báo hay chưa (gửi best-effort)
 */
public record StaffAccountCreatedResponse(
        Long id,
        String email,
        String fullName,
        UserRole role,
        String staffRoleCode,
        String staffRoleName,
        String temporaryPassword,
        boolean credentialsEmailSent) {
}
