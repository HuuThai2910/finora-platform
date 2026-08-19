package com.finora.user.integration.notification.contract;

/**
 * Request gửi mã OTP đặt lại mật khẩu — contract với finora-notification.
 */
public record OtpEmailRequest(String email, String otp) {
}
