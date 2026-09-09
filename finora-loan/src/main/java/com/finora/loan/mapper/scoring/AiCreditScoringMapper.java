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

    private static final String CITIZEN_IDENTITY_SOURCE = "RESOLVED_BY_AI_VIA_USER_SERVICE";
    private final HashingService hashingService;

    /**
     * Ánh xạ contract v17. Installment lấy từ Fineract snapshot: annuity dùng kỳ đầu,
     * gốc đều dùng kỳ lớn nhất để AI đánh giá theo nghĩa vụ trả nợ cao nhất.
     *
     * <p>{@code so_cccd} luôn gửi null: {@code BorrowerProfileResult} cố ý không mang CCCD
     * sang Loan, nên Loan không có gì để gửi và cũng không được tự tạo PII giả. Thay vào đó
     * Loan gửi {@code borrower_id} để AI tự hỏi finora-user lấy CCCD rồi tra CIC. Thiếu
     * {@code borrower_id} thì AI không tra được CIC và chấm hồ sơ như người chưa có lịch sử
     * tín dụng — thấp hơn thực chất khoảng 10 điểm đánh giá, đủ để tụt một bậc xếp hạng.</p>
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
                null,
                application.getBorrowerId()
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
