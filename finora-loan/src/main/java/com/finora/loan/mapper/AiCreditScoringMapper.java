package com.finora.loan.mapper;

import com.finora.loan.domain.BorrowerCreditProfile;
import com.finora.loan.domain.BorrowerEligibilityCheck;
import com.finora.loan.domain.IncomeVerificationStatus;
import com.finora.loan.domain.LoanApplication;
import com.finora.loan.domain.RepaymentMethod;
import com.finora.loan.domain.ScheduleCalculationSnapshot;
import com.finora.loan.integration.ai.AiCreditScoreRequest;
import com.finora.loan.service.CreditScoringInput;
import com.finora.loan.service.CreditScoringInputSources;
import com.finora.loan.service.HashingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class AiCreditScoringMapper {

    private static final String PUBLIC_RECORD_ADAPTER_POLICY = "FINORA_INTERNAL_DEFAULT_PROXY_V1";
    private final HashingService hashingService;

    /**
     * Ánh xạ đúng 13 field runtime. Installment lấy từ Fineract snapshot: annuity dùng kỳ đầu,
     * gốc đều dùng kỳ lớn nhất để AI đánh giá theo nghĩa vụ trả nợ cao nhất.
     */
    public CreditScoringInput map(
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
        CreditScoringInputSources sources = new CreditScoringInputSources(
                eligibility.getProfileSource().name(),
                eligibility.getKycVersion(),
                application.getFinancialSnapshot().getInformationSource().name(),
                schedule.getCalculationPolicyVersion(),
                creditProfile.getSource().name(),
                creditProfile.getCalculationPolicyVersion(),
                PUBLIC_RECORD_ADAPTER_POLICY
        );
        String inputJson = hashingService.toJson(request);
        return new CreditScoringInput(
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
