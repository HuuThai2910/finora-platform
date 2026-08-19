package com.finora.investment.dto.request;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * Đặt lệnh đầu tư.
 */
public record CreateOrderRequest(

        @NotNull(message = "investorId không được null")
        Long investorId,

        @NotNull @DecimalMin(value = "1000000", message = "Số tiền tối thiểu 1.000.000 VND")
        BigDecimal amount,

        BigDecimal minRate,

        BigDecimal maxRate,

        @Pattern(regexp = "^([A-E],)*[A-E]$", message = "gradeFilter: CSV A-E, ví dụ 'A,B,C'")
        String gradeFilter
) {}
