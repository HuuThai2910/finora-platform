package com.finora.notification.service;

/**
 * Interface render HTML template cho email.
 * <p>
 * Tách riêng khỏi logic gửi email để dễ đọc/sửa template
 * và có thể swap sang Thymeleaf/FreeMarker sau này.
 */
public interface EmailTemplateService {

    /**
     * Render template chào mừng người dùng mới.
     */
    String renderWelcomeEmail(String fullName);

    /**
     * Render template chứa mã OTP đặt lại mật khẩu.
     */
    String renderPasswordResetOtp(String otp);

    /**
     * Render template cảnh báo hoạt động đăng nhập bất thường.
     */
    String renderSuspiciousActivityAlert(String ipAddress, String reason);
}
