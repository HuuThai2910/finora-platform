package com.finora.loan.dto.response;

import com.finora.loan.domain.LoanApplicationStatus;
import com.finora.loan.domain.LoanPurpose;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record LoanApplicationResponse(
        Long id,
        String applicationNumber,
        String borrowerId,
        Long loanProductId,
        BigDecimal requestedAmount,
        Integer requestedTermMonths,
        LoanPurpose purposeCode,
        String purposeDetail,
        ApplicantFinancialResponse financialInformation,
        LoanProductSnapshotResponse productSnapshot,
        ScheduleCalculationSnapshotResponse calculationSnapshot,
        LocalDate expectedDisbursementDate,
        String pricingDisclosureVersion,
        Instant pricingDisclosureAcceptedAt,
        LoanApplicationStatus status,
        Instant submittedAt,
        Instant withdrawnAt,
        String withdrawalReason,
        Long latestCreditAssessmentId,
        Long version,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
