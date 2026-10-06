package com.finora.loan.domain.servicing;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Snapshot read-only từ Fineract; không phải một sổ dư nợ độc lập. */
public record LoanServicingSnapshot(Long coreLoanId, String externalId,
        String statusCode, BigDecimal principalDisbursed,
        BigDecimal principalPaid, BigDecimal principalOutstanding, BigDecimal interestCharged,
        BigDecimal interestPaid, BigDecimal interestOutstanding, BigDecimal feeOutstanding,
        BigDecimal penaltyOutstanding, BigDecimal totalOutstanding, BigDecimal overdueAmount,
        LocalDate overdueSince, int daysPastDue, LocalDate nextDueDate,
        BigDecimal nextDueAmount, LocalDate maturityDate) {}
