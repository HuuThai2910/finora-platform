package com.finora.loan.dto.decision.response;

import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.domain.application.LoanPurpose;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.domain.scoring.CreditProfileSource;
import com.finora.loan.domain.scoring.EligibilityResult;
import com.finora.loan.dto.application.response.ApplicantFinancialResponse;
import com.finora.loan.dto.application.response.LoanApplicationHistoryResponse;
import com.finora.loan.dto.application.response.ScheduleCalculationSnapshotResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminLoanReviewDetailResponse(
        String applicationNumber,
        String borrowerId,
        LoanApplicationStatus status,
        Long version,
        BigDecimal requestedAmount,
        Integer requestedTermMonths,
        LoanPurpose purposeCode,
        String purposeDetail,
        BigDecimal annualInterestRate,
        RepaymentMethod repaymentMethod,
        ApplicantFinancialResponse financialInformation,
        ScheduleCalculationSnapshotResponse schedule,
        EligibilityEvidence eligibility,
        CreditProfileEvidence creditProfile,
        AdminAssessmentEvidenceResponse assessment,
        List<LoanApplicationHistoryResponse> recentHistory,
        Instant submittedAt
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
