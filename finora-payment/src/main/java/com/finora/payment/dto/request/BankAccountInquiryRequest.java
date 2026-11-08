package com.finora.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Tra cứu chủ tài khoản trước khi liên kết (BL-001). Số tài khoản chỉ đi trong body, không lưu ở bước này. */
public record BankAccountInquiryRequest(
        @NotBlank @Size(max = 20) String bankCode,
        @NotBlank @Pattern(regexp = "[0-9]{6,19}", message = "Số tài khoản gồm 6–19 chữ số") String accountNumber
) {
}
