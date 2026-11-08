package com.finora.loan.domain.servicing.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Snapshot read-only từ Fineract; không phải một sổ dư nợ độc lập. */
public record LoanServicingSnapshot(Long coreLoanId, String externalId,
        String statusCode, BigDecimal principalDisbursed,
        BigDecimal principalPaid, BigDecimal principalOutstanding, BigDecimal interestCharged,
        BigDecimal interestPaid, BigDecimal interestOutstanding, BigDecimal feeOutstanding,
        BigDecimal penaltyOutstanding, BigDecimal totalOutstanding, BigDecimal overdueAmount,
        LocalDate overdueSince, int daysPastDue, LocalDate nextDueDate,
        BigDecimal nextDueAmount, LocalDate maturityDate, List<LoanServicingPeriod> periods) {

    public LoanServicingSnapshot {
        periods = periods == null ? List.of() : List.copyOf(periods);
    }

    /** Tương thích các phép thử/adapter chỉ cần số tổng hợp. */
    public LoanServicingSnapshot(Long coreLoanId, String externalId,
            String statusCode, BigDecimal principalDisbursed,
            BigDecimal principalPaid, BigDecimal principalOutstanding, BigDecimal interestCharged,
            BigDecimal interestPaid, BigDecimal interestOutstanding, BigDecimal feeOutstanding,
            BigDecimal penaltyOutstanding, BigDecimal totalOutstanding, BigDecimal overdueAmount,
            LocalDate overdueSince, int daysPastDue, LocalDate nextDueDate,
            BigDecimal nextDueAmount, LocalDate maturityDate) {
        this(coreLoanId, externalId, statusCode, principalDisbursed, principalPaid, principalOutstanding,
                interestCharged, interestPaid, interestOutstanding, feeOutstanding, penaltyOutstanding,
                totalOutstanding, overdueAmount, overdueSince, daysPastDue, nextDueDate, nextDueAmount,
                maturityDate, List.of());
    }
}
