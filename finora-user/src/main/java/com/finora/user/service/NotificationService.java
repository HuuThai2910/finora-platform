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

    /**
     * Gửi email thông tin đăng nhập (email + mật khẩu tạm) cho tài khoản nhân viên mới.
     *
     * @return {@code true} nếu finora-notification đã nhận yêu cầu; {@code false} khi lỗi (đã ghi log, không ném)
     */
    boolean sendStaffAccountEmail(Long userId, String email, String fullName, String roleName,
                                  String temporaryPassword);

    void sendSuspiciousActivityAlert(Long userId, String email,
                                      String ipAddress, String userAgent,
                                      String reason);
}
