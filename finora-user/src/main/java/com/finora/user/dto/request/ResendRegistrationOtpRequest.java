package com.finora.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Yêu cầu gửi lại OTP đăng ký — chỉ có tác dụng khi phiên đăng ký tạm còn hiệu lực.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ResendRegistrationOtpRequest {

    @NotBlank
    @Email
    private String email;
}
