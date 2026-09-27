package com.finora.loan.service.funding;

import com.finora.loan.config.InvestmentIntegrationProperties;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.messaging.event.LoanFundingRequestedEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import com.finora.loan.service.outbox.OutboxService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ghi funding state và event trong đúng transaction đã chốt terms. */
@Service
@RequiredArgsConstructor
public class LoanFundingRequestService {

    private static final String EVENT_TYPE = "LoanFundingRequested";
    private static final int EVENT_VERSION = 1;
    private static final String SYSTEM_ACTOR = "SYSTEM-INVESTMENT-INTEGRATION";

    private final InvestmentIntegrationProperties properties;
    private final LoanApplicationRepository applicationRepository;
    private final CreditScoringAssessmentRepository assessmentRepository;
    private final OutboxService outboxService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void request(LoanApplication application, Instant now) {
        boolean created = application.requestFunding(
                properties.fundingRound(), properties.listingVersion(), SYSTEM_ACTOR, now);
        if (!created) {
            return;
        }
        CreditScoringAssessment assessment = assessmentRepository
                .findByIdAndApplicationId(application.getLatestCreditAssessmentId(), application.getId())
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "CREDIT_ASSESSMENT_NOT_FOUND_FOR_LISTING",
                        "Không tìm thấy assessment đã dùng để duyệt hồ sơ"
                ));
        applicationRepository.saveAndFlush(application);
        outboxService.recordForPublication(
                "LoanApplication",
                application.getApplicationNumber(),
                EVENT_TYPE,
                EVENT_VERSION,
                new LoanFundingRequestedEventData(
                        application.getId(),
                        application.getApplicationNumber(),
                        properties.listingVersion(),
                        properties.fundingRound(),
                        application.getProductCodeSnapshot(),
                        application.getPurposeDetail() == null
                                ? application.getPurposeCode().getLabel()
                                : application.getPurposeCode().getLabel() + ": " + application.getPurposeDetail(),
                        null,
                        application.getPricingCreditGrade(),
                        assessment.getRiskScore(),
                        application.getRequestedAmount().toPlainString(),
                        application.getFinalAnnualInterestRate().toPlainString(),
                        application.getRequestedTermMonths(),
                        application.getRepaymentMethodSnapshot().name(),
                        application.getTermsVersion(),
                        application.getTermsHash()
                )
        );
    }
}
