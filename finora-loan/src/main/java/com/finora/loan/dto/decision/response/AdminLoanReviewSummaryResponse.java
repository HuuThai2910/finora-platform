package com.finora.loan.dto.decision.response;

import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.domain.product.RepaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;

public record AdminLoanReviewSummaryResponse(
        String applicationNumber,
        String borrowerId,
        BigDecimal requestedAmount,
        Integer requestedTermMonths,
        BigDecimal annualInterestRate,
        RepaymentMethod repaymentMethod,
        LoanApplicationStatus status,
        AdminAssessmentEvidenceResponse assessment,
        Long version,
        Instant submittedAt
) {
}
