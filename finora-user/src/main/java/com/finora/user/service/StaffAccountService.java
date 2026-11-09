package com.finora.user.service;

import com.finora.user.dto.request.CreateStaffAccountRequest;
import com.finora.user.dto.response.StaffAccountCreatedResponse;

/**
 * Tạo tài khoản nội bộ (nhân viên, quản trị viên) từ trang Phân quyền.
 * <p>
 * Tài khoản nội bộ tách hẳn tài khoản khách hàng: không có ví, không có hồ sơ khách hàng, không qua OTP đăng ký. Hệ
 * thống tự tạo user Keycloak (realm role {@code ROLE_STAFF} + {@code STAFF_*}, hoặc {@code ROLE_ADMIN}), hồ sơ
 * {@code user_profiles} và mật khẩu tạm; người dùng phải đổi mật khẩu ở lần đăng nhập đầu.
 */
public interface StaffAccountService {

    /**
     * Tạo tài khoản và trả mật khẩu tạm đúng một lần.
     * <p>
     * Lỗi: {@code 400 ROLE_NOT_ASSIGNABLE} (vai trò khách hàng), {@code 400 STAFF_ROLE_REQUIRED} /
     * {@code STAFF_ROLE_NOT_ALLOWED}, {@code 404 STAFF_ROLE_NOT_FOUND}, {@code 409 EMAIL_TAKEN} /
     * {@code PHONE_TAKEN}, {@code 500 STAFF_ACCOUNT_CREATE_FAILED} (đã dọn user Keycloak vừa tạo).
     */
    StaffAccountCreatedResponse createStaffAccount(CreateStaffAccountRequest request);
}
