package com.finora.loan.integration.fineract;

import com.finora.loan.domain.RepaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ScheduleCalculationRequest(
        Long productId,
        Long fineractProductId,
        BigDecimal amount,
        Integer termMonths,
        BigDecimal annualInterestRate,
        RepaymentMethod repaymentMethod,
        LocalDate submittedOnDate,
        LocalDate expectedDisbursementDate
) {
}
