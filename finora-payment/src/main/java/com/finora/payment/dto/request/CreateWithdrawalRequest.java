package com.finora.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Lệnh rút tiền về tài khoản ngân hàng (R4). Số tài khoản chỉ đi trong body request, được mã hóa trước khi lưu và không
 * bao giờ trả lại đầy đủ.
 */
public record CreateWithdrawalRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(max = 20) String bankCode,
        @NotBlank @Pattern(regexp = "[0-9]{6,19}", message = "Số tài khoản gồm 6–19 chữ số") String accountNumber,
        @NotBlank @Size(min = 2, max = 100) String accountHolderName
) {
}
