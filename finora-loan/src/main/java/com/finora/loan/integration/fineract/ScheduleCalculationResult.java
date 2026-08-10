package com.finora.loan.integration.fineract;

import com.finora.loan.domain.RepaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ScheduleCalculationResult(
        BigDecimal amount,
        Integer termMonths,
        BigDecimal annualInterestRate,
        RepaymentMethod repaymentMethod,
        LocalDate estimatedDisbursementDate,
        BigDecimal firstInstallment,
        BigDecimal maximumInstallment,
        BigDecimal totalPrincipal,
        BigDecimal totalInterest,
        BigDecimal totalFees,
        BigDecimal totalPenalties,
        BigDecimal totalRepayment,
        List<SchedulePeriod> periods,
        String requestSnapshotJson,
        String periodsSnapshotJson,
        String calculationPolicyVersion,
        String responseHash
) {
}
