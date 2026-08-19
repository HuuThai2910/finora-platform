package com.finora.investment.dto.request;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record AutoInvestRequest(

        @NotNull(message = "investorId không được null")
        Long investorId,

        @Pattern(regexp = "^([A-E],)*[A-E]$", message = "gradeFilter: CSV A-E")
        String gradeFilter,

        BigDecimal minRate,
        BigDecimal maxRate,
        Integer minTerm,
        Integer maxTerm,

        @NotNull @DecimalMin(value = "1000000", message = "Tối thiểu 1.000.000 VND/note")
        BigDecimal amountPerNote,

        @NotNull @DecimalMin(value = "1000000", message = "Budget tối thiểu 1.000.000 VND")
        BigDecimal totalBudget
) {}
