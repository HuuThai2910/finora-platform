package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.contract.LoanContractStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record LoanContractSummaryResponse(
        String contractNumber,
        String applicationNumber,
        BigDecimal principalAmount,
        Integer termMonths,
        BigDecimal annualInterestRate,
        BigDecimal totalRepayment,
        LoanContractStatus status,
        Instant expiresAt,
        Long version,
        Instant createdAt
) {
}
