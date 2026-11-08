package com.finora.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Bắt đầu liên kết tài khoản (BL-001): Payment tra cứu lại chủ tài khoản phía server rồi yêu cầu ngân hàng gửi OTP. Không
 * nhận tên chủ tài khoản từ app: tên luôn lấy từ ngân hàng.
 */
public record StartBankLinkRequest(
        @NotBlank @Size(max = 20) String bankCode,
        @NotBlank @Pattern(regexp = "[0-9]{6,19}", message = "Số tài khoản gồm 6–19 chữ số") String accountNumber
) {
}
