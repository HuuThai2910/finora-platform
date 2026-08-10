package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.contract.ContractDeclineReasonCode;
import com.finora.loan.domain.contract.LoanContractStatus;
import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.dto.core.response.SchedulePeriodResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LoanContractDetailResponse(
        String contractNumber,
        String applicationNumber,
        BigDecimal principalAmount,
        Integer termMonths,
        BigDecimal annualInterestRate,
        RepaymentMethod repaymentMethod,
        BigDecimal totalInterest,
        BigDecimal totalFees,
        BigDecimal totalPenalties,
        BigDecimal totalRepayment,
        BigDecimal firstInstallment,
        BigDecimal maximumInstallment,
        LocalDate expectedDisbursementDate,
        String scheduleResponseHash,
        List<SchedulePeriodResponse> schedulePeriods,
        String termsVersion,
        String documentVersion,
        String documentContent,
        String documentContentType,
        String documentHash,
        LoanContractStatus status,
        String signedBy,
        Instant signedAt,
        SignatureMethod signatureMethod,
        String declinedBy,
        Instant declinedAt,
        ContractDeclineReasonCode declineReasonCode,
        String declineReasonDetail,
        Instant expiresAt,
        Instant effectiveAt,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
}
