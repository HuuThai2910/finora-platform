package com.finora.user.domain;

/**
 * Thông tin đăng ký đang chờ xác thực OTP.
 * <p>
 * Chỉ tồn tại trong Redis với TTL ngắn. Tài khoản Keycloak và hồ sơ trong
 * PostgreSQL chỉ được tạo sau khi người dùng nhập đúng OTP, nên bản ghi này
 * là nơi duy nhất giữ mật khẩu người dùng trong lúc chờ — vì vậy lớp lưu trữ
 * phải mã hoá trường {@code password} trước khi ghi.
 *
 * @param email    email đăng ký, đồng thời là khoá tra cứu
 * @param password mật khẩu người dùng chọn — lớp lưu trữ chịu trách nhiệm mã hoá
 * @param fullName họ tên đầy đủ
 * @param phone    số điện thoại, có thể null
 * @param role     vai trò được chọn khi đăng ký
 */
public record PendingRegistration(
        String email,
        String password,
        String fullName,
        String phone,
        UserRole role) {
}
