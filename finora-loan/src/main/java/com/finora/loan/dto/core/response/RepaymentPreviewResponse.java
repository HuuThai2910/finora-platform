package com.finora.loan.dto.core.response;

import com.finora.loan.domain.product.RepaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RepaymentPreviewResponse(
        Long productId,
        BigDecimal amount,
        Integer termMonths,
        BigDecimal annualInterestRate,
        String interestRateUnit,
        RepaymentMethod repaymentMethod,
        LocalDate estimatedDisbursementDate,
        BigDecimal firstInstallment,
        BigDecimal maximumInstallment,
        BigDecimal totalPrincipal,
        BigDecimal totalInterest,
        BigDecimal totalFees,
        BigDecimal totalPenalties,
        BigDecimal totalRepayment,
        List<SchedulePeriodResponse> periods,
        String calculationPolicyVersion
) {
}
