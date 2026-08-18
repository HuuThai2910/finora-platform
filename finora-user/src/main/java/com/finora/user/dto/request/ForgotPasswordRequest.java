package com.finora.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Yêu cầu gửi OTP đặt lại mật khẩu — hệ thống sẽ gửi mã OTP qua email.
 */
public record ForgotPasswordRequest(

        @NotBlank @Email
        String email
) {
}
