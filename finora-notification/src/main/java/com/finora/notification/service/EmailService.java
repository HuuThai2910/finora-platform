package com.finora.notification.service;

/**
 * Interface dịch vụ gửi email.
 * <p>
 * Email là best-effort: lỗi gửi được log nhưng không ném exception
 * để không ảnh hưởng luồng nghiệp vụ chính.
 */
public interface EmailService {

    /**
     * Gửi email chào mừng người dùng mới đăng ký.
     */
    void sendWelcomeEmail(String toEmail, String fullName);

    /**
     * Gửi email chứa mã OTP đặt lại mật khẩu.
     */
    void sendPasswordResetOtp(String toEmail, String otp);

    /**
     * Gửi email cảnh báo hoạt động đăng nhập bất thường.
     */
    void sendSuspiciousActivityAlert(String toEmail, String ipAddress, String reason);
}
