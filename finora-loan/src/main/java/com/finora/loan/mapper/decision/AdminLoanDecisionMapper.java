package com.finora.loan.mapper.decision;

import com.finora.loan.domain.application.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.scoring.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.dto.application.response.ApplicantFinancialResponse;
import com.finora.loan.dto.application.response.LoanApplicationHistoryResponse;
import com.finora.loan.dto.decision.response.AdminAssessmentEvidenceResponse;
import com.finora.loan.dto.decision.response.AdminLoanDecisionResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewDetailResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewSummaryResponse;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AdminLoanDecisionMapper {

    private final ScheduleCalculationSnapshotMapper scheduleMapper;

    public AdminLoanDecisionMapper(ScheduleCalculationSnapshotMapper scheduleMapper) {
        this.scheduleMapper = scheduleMapper;
    }

    public AdminLoanReviewSummaryResponse toSummary(
            LoanApplication application,
            CreditScoringAssessment assessment
    ) {
        return new AdminLoanReviewSummaryResponse(
                application.getApplicationNumber(),
                application.getBorrowerId(),
                application.getRequestedAmount(),
                application.getRequestedTermMonths(),
                application.getAnnualInterestRateSnapshot(),
                application.getRepaymentMethodSnapshot(),
                application.getStatus(),
                assessment(assessment),
                application.getVersion(),
                application.getSubmittedAt()
        );
    }

    public AdminLoanReviewDetailResponse toDetail(
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            BorrowerEligibilityCheck eligibility,
            BorrowerCreditProfile creditProfile,
            CreditScoringAssessment assessment,
            List<LoanApplicationHistoryResponse> recentHistory
    ) {
        return new AdminLoanReviewDetailResponse(
                application.getApplicationNumber(),
                application.getBorrowerId(),
                application.getStatus(),
                application.getVersion(),
                application.getRequestedAmount(),
                application.getRequestedTermMonths(),
                application.getPurposeCode(),
                application.getPurposeDetail(),
                application.getAnnualInterestRateSnapshot(),
                application.getRepaymentMethodSnapshot(),
                financial(application.getFinancialSnapshot()),
                scheduleMapper.toResponse(schedule),
                eligibility(eligibility),
                creditProfile(creditProfile),
                assessment(assessment),
                recentHistory,
                application.getSubmittedAt()
        );
    }

    public AdminLoanDecisionResponse toDecision(LoanApplication application, LoanContract contract) {
        return new AdminLoanDecisionResponse(
                application.getApplicationNumber(),
                application.getStatus(),
                application.getVersion(),
                application.getAdminDecisionReasonCode(),
                application.getAdminDecisionPolicyVersion(),
                application.getAdminDecidedBy(),
                application.getAdminDecidedAt(),
                contract == null ? null : contract.getContractNumber(),
                contract == null ? null : contract.getStatus(),
                contract == null ? null : contract.getVersion(),
                contract == null ? null : contract.getDocumentHash(),
                contract == null ? null : contract.getExpiresAt()
        );
    }

    private AdminAssessmentEvidenceResponse assessment(CreditScoringAssessment assessment) {
        if (assessment == null) {
            return null;
        }
        return new AdminAssessmentEvidenceResponse(
                assessment.getId(),
                assessment.getStatus(),
                assessment.getActualModelVersion(),
                assessment.getPdProbability(),
                assessment.getRiskScore(),
                assessment.getEvaluationScore(),
                assessment.getCreditGrade(),
                assessment.getSuggestedLimit(),
                assessment.getAiRecommendation(),
                assessment.getRejectionReason(),
                assessment.getDecisionPolicyVersion(),
                assessment.getScoredAt()
        );
    }

    private ApplicantFinancialResponse financial(ApplicantFinancialSnapshot snapshot) {
        return new ApplicantFinancialResponse(
                snapshot.getDeclaredMonthlyIncome(), snapshot.getAnnualIncomeSnapshot(),
                snapshot.getEmploymentLengthMonths(), snapshot.getEducationLevel(), snapshot.getHomeOwnership(),
                snapshot.getMonthlyDebtObligations(), snapshot.getDtiSnapshot(),
                snapshot.getInformationSource(), snapshot.getCapturedAt()
        );
    }

    private AdminLoanReviewDetailResponse.EligibilityEvidence eligibility(BorrowerEligibilityCheck check) {
        if (check == null) {
            return null;
        }
        return new AdminLoanReviewDetailResponse.EligibilityEvidence(
                check.getAge(), check.getKycStatus(), check.getProfileSource(), check.getEligibilityResult(),
                check.getReasonCode(), check.getPolicyVersion(), check.getCheckedAt()
        );
    }

    private AdminLoanReviewDetailResponse.CreditProfileEvidence creditProfile(BorrowerCreditProfile profile) {
        if (profile == null) {
            return null;
        }
        return new AdminLoanReviewDetailResponse.CreditProfileEvidence(
                profile.isHasInternalCreditHistory(), profile.getInternalDelinquenciesLast2Years(),
                profile.getInternalDefaultedLoanCount(), profile.getCompletedLoanCount(),
                profile.getSource(), profile.getCalculationPolicyVersion()
        );
    }
}
