package com.finora.payment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Thanh toán một lần khớp trên sổ lệnh Notes từ khoản giữ của người mua.
 *
 * @param orderReference      mã lệnh mua đã dùng lúc giữ tiền, để đối chiếu đúng khoản giữ
 * @param sellerId            ví nhận tiền
 * @param amount              số tiền lấy ra khỏi khoản giữ
 * @param platformFee         phần phí trong {@code amount}; người bán nhận {@code amount − platformFee}
 * @param settlementReference khóa chống trùng, duy nhất mỗi lần khớp
 */
public record SettleHoldRequest(
        @NotBlank @Size(max = 100) String orderReference,
        @NotBlank @Size(max = 100) String sellerId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal platformFee,
        @NotBlank @Size(max = 100) String settlementReference
) {
}
