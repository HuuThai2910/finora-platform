package com.finora.loan.mapper.application;

import com.finora.loan.domain.application.entity.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.scoring.entity.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.entity.CreditScoringAssessment;
import com.finora.loan.domain.scoring.enums.CreditAssessmentStatus;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.Background;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.CreditAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.LoanRequest;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.RepaymentCapacity;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import org.springframework.stereotype.Component;

/**
 * Dựng hồ sơ người vay cho nhà đầu tư từ các snapshot Loan đã lưu. Mapper chỉ chép theo allowlist:
 * field nào không có ở đây thì không bao giờ ra khỏi Loan qua API này. Lịch sử tín dụng và bảng luật
 * đã chấm cố ý không có.
 */
@Component
public class InvestorBorrowerProfileMapper {

    public InvestorBorrowerProfileResponse toResponse(
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            BorrowerEligibilityCheck eligibility,
            CreditScoringAssessment assessment
    ) {
        ApplicantFinancialSnapshot financial = application.getFinancialSnapshot();
        boolean scored = assessment != null && assessment.getStatus() == CreditAssessmentStatus.SUCCEEDED;
        return new InvestorBorrowerProfileResponse(
                application.getApplicationNumber(),
                loan(application, schedule),
                new RepaymentCapacity(
                        financial.getDeclaredMonthlyIncome(), financial.getMonthlyDebtObligations(),
                        financial.getDtiSnapshot(), financial.getEmploymentLengthMonths(),
                        financial.getInformationSource(), financial.getCapturedAt()),
                new Background(
                        eligibility == null ? null : eligibility.getAge(),
                        eligibility == null ? null : eligibility.getKycStatus(),
                        eligibility == null ? null : eligibility.getProfileSource(),
                        eligibility == null ? null : eligibility.getCheckedAt(),
                        financial.getHomeOwnership(), financial.getEducationLevel()),
                // Lần chấm lỗi/đang chờ thì không công bố con số nào, kể cả khi còn snapshot cũ.
                scored ? assessment(application, assessment) : null
        );
    }

    private LoanRequest loan(LoanApplication application, ScheduleCalculationSnapshot schedule) {
        return new LoanRequest(
                application.getPurposeCode(),
                application.getPurposeCode().getLabel(),
                application.getPurposeDetail(),
                application.getRequestedAmount(),
                application.getRequestedTermMonths(),
                application.getRepaymentMethodSnapshot(),
                application.getFinalAnnualInterestRate(),
                schedule == null ? null : schedule.getFirstInstallment(),
                schedule == null ? null : schedule.getMaximumInstallment(),
                schedule == null ? null : schedule.getTotalRepayment(),
                schedule == null ? null : schedule.getExpectedDisbursementDate()
        );
    }

    private CreditAssessment assessment(LoanApplication application, CreditScoringAssessment assessment) {
        return new CreditAssessment(
                assessment.getEvaluationScore(), assessment.getCreditGrade(), assessment.getPdProbability(),
                assessment.getRiskScore(), application.getDecisionSource(), assessment.getScoredAt());
    }
}
