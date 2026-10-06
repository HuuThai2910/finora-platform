package com.finora.loan.dto.servicing.response;

import com.finora.loan.domain.servicing.FinoraLoanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record LoanServicingSummaryResponse(
        String loanNumber,
        String applicationNumber,
        String contractNumber,
        FinoraLoanStatus status,
        BigDecimal principalAmount,
        BigDecimal principalOutstanding,
        BigDecimal totalOutstanding,
        BigDecimal overdueAmount,
        int daysPastDue,
        LocalDate nextDueDate,
        BigDecimal nextDueAmount,
        LocalDate maturityDate,
        String currency,
        String source,
        Instant dataAsOf,
        Instant lastSyncedAt,
        boolean stale
) {
}
