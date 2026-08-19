package com.finora.notification.dto.request;

/**
 * Request gửi mã OTP đặt lại mật khẩu.
 */
public record OtpEmailRequest(String email, String otp) {
}
