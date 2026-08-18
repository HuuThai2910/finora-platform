package com.finora.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Yêu cầu đặt lại mật khẩu sau khi đã nhận OTP qua email.
 */
public record ResetPasswordRequest(

        @NotBlank @Email
        String email,

        /** Mã OTP 6 chữ số đã gửi qua email */
        @NotBlank @Size(min = 6, max = 6)
        String otp,

        @NotBlank @Size(min = 8, max = 64)
        String newPassword
) {
}
