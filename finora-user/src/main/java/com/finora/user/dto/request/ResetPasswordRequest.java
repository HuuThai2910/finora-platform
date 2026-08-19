package com.finora.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Yêu cầu đặt lại mật khẩu sau khi đã nhận OTP qua email.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequest {

    @NotBlank
    @Email
    private String email;

    /** Mã OTP 6 chữ số đã gửi qua email */
    @NotBlank
    @Size(min = 6, max = 6)
    private String otp;

    @NotBlank
    @Size(min = 8, max = 64)
    private String newPassword;
}
