package com.finora.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Yêu cầu xác thực OTP đăng ký — bước 2, tài khoản chỉ được tạo sau khi mã đúng.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VerifyRegistrationRequest {

    @NotBlank
    @Email
    private String email;

    /** Mã OTP 6 chữ số đã gửi qua email khi đăng ký */
    @NotBlank
    @Size(min = 6, max = 6)
    private String otp;
}
