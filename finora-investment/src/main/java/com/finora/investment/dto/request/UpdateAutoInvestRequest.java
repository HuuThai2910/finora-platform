package com.finora.investment.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Lưu toàn bộ tiêu chí Auto-Invest.
 *
 * <p>Hạng chỉ kiểm định dạng, cùng ràng buộc {@code credit_grade} bên finora-ai: bảng hạng là
 * cấu hình động admin sửa được, hạng không còn tồn tại chỉ đơn giản là không bao giờ khớp.
 * {@code amountPerLoan} không bắt chia hết mệnh giá — worker làm tròn xuống lúc đặt lệnh.</p>
 */
public record UpdateAutoInvestRequest(
        @NotNull
        Boolean enabled,

        @NotEmpty @Size(max = 10)
        List<@NotNull @Pattern(regexp = "^[A-Z][A-Z0-9+-]{0,7}$") String> grades,

        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4)
        BigDecimal minAnnualRate,

        @NotNull @Min(1) @Max(120)
        Integer maxTermMonths,

        @NotNull @DecimalMin("1000") @Digits(integer = 16, fraction = 2)
        BigDecimal amountPerLoan
) {
}
