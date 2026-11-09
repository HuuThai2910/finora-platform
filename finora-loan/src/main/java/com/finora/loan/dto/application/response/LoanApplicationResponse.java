package com.finora.loan.dto.application.response;

import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.application.enums.LoanDecisionSource;
import com.finora.loan.domain.application.enums.LoanPurpose;

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
        ScheduleCalculationSnapshotResponse finalCalculationSnapshot,
        BigDecimal finalAnnualInterestRate,
        String pricingCreditGrade,
        BigDecimal pricingAdjustmentPercentagePoints,
        String pricingPolicyVersion,
        LoanDecisionSource decisionSource,
        LocalDate expectedDisbursementDate,
        String pricingDisclosureVersion,
        Instant pricingDisclosureAcceptedAt,
        LoanApplicationStatus status,
        Instant submittedAt,
        Instant withdrawnAt,
        String withdrawalReason,
        TermsConfirmationResponse termsConfirmation,
        LoanFundingResponse funding,
        Long latestCreditAssessmentId,
        Long version,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt,
        /** Mã hiển thị {@code HS-yyyyMMdd-XXXX} cho người dùng; {@code applicationNumber} vẫn là khóa tra cứu API. */
        String applicationCode
) {
}
