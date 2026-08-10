package com.finora.loan.dto.request;

import com.finora.loan.domain.RepaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateLoanProductRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{2,49}") String code,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 5000) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal minAmount,
        @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal maxAmount,
        @NotNull @Positive Integer minTermMonths,
        @NotNull @Positive Integer maxTermMonths,
        @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 3, fraction = 4) BigDecimal annualInterestRate,
        @NotNull RepaymentMethod repaymentMethod
) {
}
