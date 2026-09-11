package com.finora.loan.dto.decision.response;

import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.domain.application.LoanDecisionSource;
import com.finora.loan.domain.product.RepaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;

public record AdminLoanReviewSummaryResponse(
        String applicationNumber,
        String borrowerId,
        BigDecimal requestedAmount,
        Integer requestedTermMonths,
        /** Giữ tên cũ để client hiện hữu hiểu đây là base rate lúc borrower nộp hồ sơ. */
        BigDecimal annualInterestRate,
        BigDecimal finalAnnualInterestRate,
        String pricingCreditGrade,
        BigDecimal pricingAdjustmentPercentagePoints,
        LoanDecisionSource decisionSource,
        RepaymentMethod repaymentMethod,
        LoanApplicationStatus status,
        AdminAssessmentEvidenceResponse assessment,
        Long version,
        Instant submittedAt,
        /** ID quản trị viên đã duyệt hoặc từ chối; {@code null} khi hồ sơ chưa có quyết định. */
        String adminDecidedBy,
        Instant adminDecidedAt
) {
}
