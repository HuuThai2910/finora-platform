package com.finora.user.integration.notification.contract;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request gửi mã OTP đặt lại mật khẩu — contract với finora-notification.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OtpEmailRequest {

    private String email;
    private String otp;
}
