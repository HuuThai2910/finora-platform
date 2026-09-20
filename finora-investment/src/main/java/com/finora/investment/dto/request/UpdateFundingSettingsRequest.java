package com.finora.investment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Đổi tham số gọi vốn của sàn.
 *
 * <p>Hạ {@code noteDenomination} giúp khoản vay có số lẻ vẫn gọi đủ vốn: mục tiêu phải
 * chia hết cho mệnh giá, nên mệnh giá càng nhỏ thì phần bị cắt càng ít. Đánh đổi là số
 * Note phát hành tăng lên tương ứng.</p>
 */
public record UpdateFundingSettingsRequest(
        @NotNull @DecimalMin("1000") @Digits(integer = 16, fraction = 2)
        BigDecimal noteDenomination,

        @NotNull @DecimalMin("1000") @Digits(integer = 16, fraction = 2)
        BigDecimal minInvestmentAmount,

        @NotNull @Min(1) @Max(90)
        Integer fundingDays
) {
}
