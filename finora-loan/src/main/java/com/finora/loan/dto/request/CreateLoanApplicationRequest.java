package com.finora.loan.dto.request;

import com.finora.loan.domain.EducationLevel;
import com.finora.loan.domain.HomeOwnership;
import com.finora.loan.domain.LoanPurpose;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateLoanApplicationRequest(
        @NotNull @Positive Long loanProductId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal requestedAmount,
        @NotNull @Positive Integer requestedTermMonths,
        @NotNull LoanPurpose purposeCode,
        @Size(max = 500) String purposeDetail,
        @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal declaredMonthlyIncome,
        @PositiveOrZero Integer employmentLengthMonths,
        EducationLevel educationLevel,
        @NotNull HomeOwnership homeOwnership,
        @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal monthlyDebtObligations,
        @NotNull @FutureOrPresent LocalDate expectedDisbursementDate,
        @NotNull @Size(max = 50) String pricingDisclosureVersion,
        @NotNull Boolean pricingDisclosureAccepted
) {
}
