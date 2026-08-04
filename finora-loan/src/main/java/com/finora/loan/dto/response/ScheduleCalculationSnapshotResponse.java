package com.finora.loan.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ScheduleCalculationSnapshotResponse(
        Long id,
        LocalDate expectedDisbursementDate,
        BigDecimal totalPrincipal,
        BigDecimal totalInterest,
        BigDecimal totalFees,
        BigDecimal totalPenalties,
        BigDecimal totalRepayment,
        BigDecimal firstInstallment,
        BigDecimal maximumInstallment,
        String calculationPolicyVersion,
        Instant calculatedAt
) {
}
