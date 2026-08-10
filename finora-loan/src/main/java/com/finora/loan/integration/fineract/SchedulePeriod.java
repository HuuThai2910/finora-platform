package com.finora.loan.integration.fineract;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SchedulePeriod(
        Integer period,
        LocalDate fromDate,
        LocalDate dueDate,
        Integer daysInPeriod,
        BigDecimal principal,
        BigDecimal interest,
        BigDecimal fees,
        BigDecimal penalties,
        BigDecimal totalDue,
        BigDecimal outstandingBalance
) {
}
