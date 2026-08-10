package com.finora.loan.dto.core.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SchedulePeriodResponse(
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
