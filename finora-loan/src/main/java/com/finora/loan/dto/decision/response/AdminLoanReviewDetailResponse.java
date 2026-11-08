package com.finora.loan.dto.decision.response;

import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.application.enums.LoanDecisionSource;
import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.domain.application.enums.TermsConfirmationStatus;
import com.finora.loan.domain.product.enums.RepaymentMethod;
import com.finora.loan.domain.scoring.enums.BorrowerKycStatus;
import com.finora.loan.domain.scoring.enums.BorrowerProfileSource;
import com.finora.loan.domain.scoring.enums.CreditProfileSource;
import com.finora.loan.domain.scoring.enums.EligibilityResult;
import com.finora.loan.dto.application.response.ApplicantFinancialResponse;
import com.finora.loan.dto.application.response.LoanApplicationHistoryResponse;
import com.finora.loan.dto.application.response.LoanApplicationTimelineItemResponse;
import com.finora.loan.dto.application.response.ScheduleCalculationSnapshotResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminLoanReviewDetailResponse(
        /** ID số của hồ sơ vay; explorer bằng chứng blockchain tra {@code LOAN_APPLICATION} theo ID này (B73). */
        Long id,
        String applicationNumber,
        String borrowerId,
        LoanApplicationStatus status,
        Long version,
        BigDecimal requestedAmount,
        Integer requestedTermMonths,
        LoanPurpose purposeCode,
        String purposeDetail,
        BigDecimal annualInterestRate,
        BigDecimal finalAnnualInterestRate,
        String pricingCreditGrade,
        BigDecimal pricingAdjustmentPercentagePoints,
        String pricingPolicyVersion,
        LoanDecisionSource decisionSource,
        RepaymentMethod repaymentMethod,
        ApplicantFinancialResponse financialInformation,
        ScheduleCalculationSnapshotResponse schedule,
        ScheduleCalculationSnapshotResponse initialSchedule,
        ScheduleCalculationSnapshotResponse finalSchedule,
        EligibilityEvidence eligibility,
        CreditProfileEvidence creditProfile,
        AdminAssessmentEvidenceResponse assessment,
        List<LoanApplicationHistoryResponse> recentHistory,
        TermsConfirmationStatus termsConfirmationStatus,
        String termsVersion,
        Instant termsExpiresAt,
        Instant submittedAt,
        /** ID quản trị viên đã duyệt hoặc từ chối; {@code null} khi hồ sơ chưa có quyết định. */
        String adminDecidedBy,
        Instant adminDecidedAt,
        /** Số hợp đồng; {@code null} cho tới khi Contract được lập (sau khi khoản vay đủ vốn). */
        String contractNumber,
        /**
         * Mã lý do AI chuyển hồ sơ cho người thẩm định (vd. {@code SCORE_IN_REVIEW_BAND}); rỗng khi AI không chuyển
         * hoặc lần chấm diễn ra trước khi Loan lưu trường này.
         */
        List<String> aiReviewReasons,
        /**
         * Lịch sử xử lý đầy đủ (mới nhất trước): thẩm định, gọi vốn, ký hợp đồng gồm nhà đầu tư rút và gọi vốn bổ sung,
         * giải ngân, cơ cấu/gia hạn, thu hồi nợ, đóng khoản vay.
         */
        List<LoanApplicationTimelineItemResponse> timeline
) {
    public record EligibilityEvidence(
            Integer age,
            BorrowerKycStatus kycStatus,
            BorrowerProfileSource profileSource,
            EligibilityResult result,
            String reasonCode,
            String policyVersion,
            Instant checkedAt
    ) {
    }

    public record CreditProfileEvidence(
            boolean hasInternalCreditHistory,
            int internalDelinquenciesLast2Years,
            int internalDefaultedLoanCount,
            int completedLoanCount,
            CreditProfileSource source,
            String calculationPolicyVersion
    ) {
    }
}
