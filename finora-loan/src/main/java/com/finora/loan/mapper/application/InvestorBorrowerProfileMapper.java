package com.finora.loan.mapper.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.finora.loan.domain.application.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.scoring.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.Background;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.CreditAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.CreditHistory;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.LoanRequest;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.RepaymentCapacity;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.RuleResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Dựng hồ sơ người vay cho nhà đầu tư từ các snapshot Loan đã lưu. Mapper chỉ chép theo allowlist:
 * field nào không có ở đây thì không bao giờ ra khỏi Loan qua API này.
 */
@Component
public class InvestorBorrowerProfileMapper {

    public InvestorBorrowerProfileResponse toResponse(
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            BorrowerEligibilityCheck eligibility,
            BorrowerCreditProfile creditProfile,
            CreditScoringAssessment assessment,
            JsonNode ruleTrace
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
                creditHistory(creditProfile),
                // Lần chấm lỗi/đang chờ thì không công bố con số hay luật nào, kể cả khi còn snapshot cũ.
                scored ? assessment(application, assessment) : null,
                scored ? ruleResults(ruleTrace) : List.of()
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

    private CreditHistory creditHistory(BorrowerCreditProfile profile) {
        if (profile == null) {
            return null;
        }
        return new CreditHistory(
                profile.isHasInternalCreditHistory(), profile.getCompletedLoanCount(),
                profile.getInternalDelinquenciesLast2Years(), profile.getInternalDefaultedLoanCount(),
                profile.getSource());
    }

    private CreditAssessment assessment(LoanApplication application, CreditScoringAssessment assessment) {
        return new CreditAssessment(
                assessment.getEvaluationScore(), assessment.getCreditGrade(), assessment.getPdProbability(),
                assessment.getRiskScore(), application.getDecisionSource(), assessment.getScoredAt());
    }

    /**
     * Chép {@code rule_trace} theo allowlist. Luật là dữ liệu admin cấu hình nên phần tử lạ (không phải
     * object) bị bỏ qua; {@code gia_tri} không phải số/chuỗi thành null để không lọt cấu trúc lồng.
     */
    private List<RuleResult> ruleResults(JsonNode ruleTrace) {
        if (ruleTrace == null || !ruleTrace.isArray()) {
            return List.of();
        }
        List<RuleResult> results = new ArrayList<>();
        for (JsonNode item : ruleTrace) {
            if (!item.isObject()) {
                continue;
            }
            results.add(new RuleResult(
                    text(item, "ma"), text(item, "mo_ta"), text(item, "truong"), scalar(item.get("gia_tri")),
                    integer(item, "diem"), integer(item, "toi_da"), decimal(item, "trong_so"),
                    item.path("thieu_du_lieu").asBoolean(false)));
        }
        return List.copyOf(results);
    }

    private static String text(JsonNode item, String key) {
        JsonNode value = item.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static Integer integer(JsonNode item, String key) {
        JsonNode value = item.get(key);
        return value != null && value.isNumber() ? value.intValue() : null;
    }

    private static BigDecimal decimal(JsonNode item, String key) {
        JsonNode value = item.get(key);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private static JsonNode scalar(JsonNode value) {
        return value != null && (value.isNumber() || value.isTextual()) ? value : NullNode.getInstance();
    }
}
