package com.finora.loan.domain.contract;

import com.finora.loan.domain.product.RepaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Bản chụp toàn bộ số tiền và lịch dự kiến đã dùng để tạo nội dung Contract. */
public record LoanContractTerms(
        BigDecimal principalAmount,
        Integer termMonths,
        BigDecimal annualInterestRate,
        RepaymentMethod repaymentMethod,
        Long calculationSnapshotId,
        BigDecimal totalInterest,
        BigDecimal totalFees,
        BigDecimal totalPenalties,
        BigDecimal totalRepayment,
        BigDecimal firstInstallment,
        BigDecimal maximumInstallment,
        String scheduleResponseHash,
        LocalDate expectedDisbursementDate
) {
}
