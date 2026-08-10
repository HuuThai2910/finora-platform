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

    private static final String PUBLIC_RECORD_ADAPTER_POLICY = "FINORA_INTERNAL_DEFAULT_PROXY_V1";
    private final HashingService hashingService;

    /**
     * Ánh xạ đúng 13 field runtime. Installment lấy từ Fineract snapshot: annuity dùng kỳ đầu,
     * gốc đều dùng kỳ lớn nhất để AI đánh giá theo nghĩa vụ trả nợ cao nhất.
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
                creditProfile.getInternalDelinquenciesLast2Years(),
                creditProfile.getInternalDefaultedLoanCount(),
                installment
        );
        CreditScoringSourceSnapshot sources = new CreditScoringSourceSnapshot(
                eligibility.getProfileSource().name(),
                eligibility.getKycVersion(),
                eligibility.getPolicyVersion(),
                application.getFinancialSnapshot().getInformationSource().name(),
                schedule.getCalculationPolicyVersion(),
                creditProfile.getSource().name(),
                creditProfile.getCalculationPolicyVersion(),
                PUBLIC_RECORD_ADAPTER_POLICY
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
