package com.finora.loan.dto.application.response;

import com.finora.loan.dto.core.response.SchedulePeriodResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
        List<SchedulePeriodResponse> periods,
        String calculationPolicyVersion,
        Instant calculatedAt
) {
}
