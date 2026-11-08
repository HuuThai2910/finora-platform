package com.finora.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Mã OTP ngân hàng gửi khi liên kết tài khoản (BL-001). */
public record ConfirmBankLinkRequest(
        @NotBlank @Pattern(regexp = "[0-9]{6}", message = "Mã xác thực gồm 6 chữ số") String otp
) {
}
