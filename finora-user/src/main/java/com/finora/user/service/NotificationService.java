package com.finora.user.service;

/**
 * Interface dịch vụ gửi thông báo — best-effort, không ném exception khi notification service lỗi.
 */
public interface NotificationService {

    void sendWelcomeEmail(Long userId, String email, String fullName);

    void sendPasswordResetOtp(Long userId, String email, String otp);

    /**
     * Gửi OTP xác thực đăng ký. Chưa có userId vì tài khoản chỉ được tạo sau khi mã đúng.
     */
    void sendRegistrationOtp(String email, String otp);

    void sendSuspiciousActivityAlert(Long userId, String email,
                                      String ipAddress, String userAgent,
                                      String reason);
}
