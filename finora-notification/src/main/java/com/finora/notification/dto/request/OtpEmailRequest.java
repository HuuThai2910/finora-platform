package com.finora.notification.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request gửi mã OTP đặt lại mật khẩu.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OtpEmailRequest {

    @NotBlank @Email @Size(max = 254)
    private String email;
    /** Mã 6 chữ số do finora-user sinh; chặn nội dung tùy ý trong email mang danh FINORA. */
    @NotBlank @Pattern(regexp = "\\d{6}")
    private String otp;
}
