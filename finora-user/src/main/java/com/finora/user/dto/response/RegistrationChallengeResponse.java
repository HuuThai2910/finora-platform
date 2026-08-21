package com.finora.user.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Phản hồi bước 1 của đăng ký — báo cho client biết OTP đã được gửi đi đâu và còn hiệu lực bao lâu.
 * <p>
 * Không chứa token vì tài khoản chưa được tạo.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RegistrationChallengeResponse {

    private String email;

    /** Email đã che bớt để hiển thị trên màn nhập OTP */
    private String maskedEmail;

    /** Số chữ số của mã OTP */
    private int otpLength;

    /** Thời gian sống còn lại của OTP, tính bằng giây */
    private long otpExpiresInSeconds;
}
