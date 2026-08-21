package com.finora.notification.dto.request;

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

    private String email;
    private String otp;
}
