package com.finora.loan.mapper.scoring;

import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.domain.scoring.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.IncomeVerificationStatus;
import com.finora.loan.integration.ai.contract.AiCreditScoreRequest;
import com.finora.loan.support.HashingService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiCreditScoringMapper {

    private static final String CITIZEN_IDENTITY_SOURCE = "NOT_AVAILABLE_UNTIL_USER_SERVICE_CONTRACT";
    private final HashingService hashingService;

    /**
     * Ánh xạ contract v17. Installment lấy từ Fineract snapshot: annuity dùng kỳ đầu,
     * gốc đều dùng kỳ lớn nhất để AI đánh giá theo nghĩa vụ trả nợ cao nhất.
     * CCCD chưa có trong contract User Service nên chủ động gửi null, không tự tạo và không lưu PII giả.
     */
    public CreditScoringMapping map(
            LoanApplication application,
            BorrowerEligibilityCheck eligibility,
            BorrowerCreditProfile creditProfile,
            ScheduleCalculationSnapshot schedule
    ) {
        BigDecimal installment = application.getRepaymentMethodSnapshot() == RepaymentMethod.EQUAL_PRINCIPAL
                ? schedule.getMaximumInstallment()
                : schedule.getFirstInstallment();
        AiCreditScoreRequest request = new AiCreditScoreRequest(
                eligibility.getAge(),
                employmentLength(application.getFinancialSnapshot().getEmploymentLengthMonths()),
                application.getFinancialSnapshot().getAnnualIncomeSnapshot(),
                application.getRequestedAmount(),
                application.getFinancialSnapshot().getHomeOwnership().name(),
                application.getPurposeCode().getAiValue(),
                application.getAnnualInterestRateSnapshot(),
                application.getRequestedTermMonths(),
                verificationStatus(eligibility.getIncomeVerificationStatus()),
                application.getFinancialSnapshot().getDtiSnapshot(),
                installment,
                "DECLINING_BALANCE",
                null
        );
        CreditScoringSourceSnapshot sources = new CreditScoringSourceSnapshot(
                eligibility.getProfileSource().name(),
                eligibility.getKycVersion(),
                eligibility.getPolicyVersion(),
                application.getFinancialSnapshot().getInformationSource().name(),
                schedule.getCalculationPolicyVersion(),
                creditProfile.getSource().name(),
                creditProfile.getCalculationPolicyVersion(),
                CITIZEN_IDENTITY_SOURCE
        );
        String inputJson = hashingService.toJson(request);
        return new CreditScoringMapping(
                request,
                sources,
                inputJson,
                hashingService.toJson(sources),
                hashingService.sha256(request)
        );
    }

    private String employmentLength(Integer months) {
        if (months == null) {
            return null;
        }
        if (months < 12) {
            return "< 1 year";
        }
        int years = months / 12;
        return years >= 10 ? "10+ years" : years + (years == 1 ? " year" : " years");
    }

    private String verificationStatus(IncomeVerificationStatus status) {
        return switch (status) {
            case VERIFIED -> "Verified";
            case SOURCE_VERIFIED -> "Source Verified";
            case NOT_VERIFIED -> "Not Verified";
        };
    }
}
