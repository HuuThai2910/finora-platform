package com.finora.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Yêu cầu kiểm tra OTP đặt lại mật khẩu trước khi cho nhập mật khẩu mới.
 * <p>
 * Bước kiểm tra này không tiêu huỷ mã: client vẫn phải gửi lại đúng mã đó
 * trong {@link ResetPasswordRequest} và backend mới là bên quyết định cuối.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VerifyResetOtpRequest {

    @NotBlank
    @Email
    private String email;

    /** Mã OTP 6 chữ số đã gửi qua email */
    @NotBlank
    @Size(min = 6, max = 6)
    private String otp;
}
