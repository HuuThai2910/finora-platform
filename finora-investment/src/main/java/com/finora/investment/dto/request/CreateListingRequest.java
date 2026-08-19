package com.finora.investment.dto.request;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * Tạo listing mới trên sàn P2P.
 */
public record CreateListingRequest(

        @NotNull(message = "loanApplicationId không được null")
        Long loanApplicationId,

        @NotNull(message = "borrowerId không được null")
        Long borrowerId,

        @NotNull @DecimalMin(value = "1000000", message = "Số tiền tối thiểu 1.000.000 VND")
        BigDecimal amount,

        @NotNull @Min(value = 1, message = "Kỳ hạn tối thiểu 1 tháng")
        Integer termMonths,

        @NotBlank(message = "Grade không được trống")
        @Pattern(regexp = "^[A-E]$", message = "Grade phải từ A đến E")
        String grade,

        @NotNull @DecimalMin(value = "0.01", message = "Lãi suất phải > 0")
        BigDecimal interestRate
) {}
