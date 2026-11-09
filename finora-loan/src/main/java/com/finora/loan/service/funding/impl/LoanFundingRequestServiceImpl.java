package com.finora.loan.service.funding.impl;

import com.finora.loan.config.InvestmentIntegrationProperties;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.scoring.entity.CreditScoringAssessment;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.messaging.event.LoanFundingRequestedEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import com.finora.loan.service.funding.LoanFundingRequestService;
import com.finora.loan.service.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Ghi funding state và event trong đúng transaction đã chốt terms.
 */
@Service
@RequiredArgsConstructor
public class LoanFundingRequestServiceImpl implements LoanFundingRequestService {

    private static final String EVENT_TYPE = "LoanFundingRequested";
    private static final int EVENT_VERSION = 1;
    private static final String SYSTEM_ACTOR = "SYSTEM-INVESTMENT-INTEGRATION";

    private final InvestmentIntegrationProperties investmentIntegrationProperties;
    private final LoanApplicationRepository loanApplicationRepository;
    private final CreditScoringAssessmentRepository creditScoringAssessmentRepository;
    private final OutboxService outboxService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void request(LoanApplication application, Instant now) {
        boolean created = application.requestFunding(
                investmentIntegrationProperties.fundingRound(), investmentIntegrationProperties.listingVersion(),
                SYSTEM_ACTOR, now);
        if (!created) {
            return;
        }
        CreditScoringAssessment assessment = creditScoringAssessmentRepository
                .findByIdAndApplicationId(application.getLatestCreditAssessmentId(), application.getId())
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "CREDIT_ASSESSMENT_NOT_FOUND_FOR_LISTING",
                        "Không tìm thấy assessment đã dùng để duyệt hồ sơ"
                ));
        loanApplicationRepository.saveAndFlush(application);
        outboxService.recordForPublication(
                "LoanApplication",
                application.getApplicationNumber(),
                EVENT_TYPE,
                EVENT_VERSION,
                new LoanFundingRequestedEventData(
                        application.getId(),
                        application.getApplicationNumber(),
                        investmentIntegrationProperties.listingVersion(),
                        investmentIntegrationProperties.fundingRound(),
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
                        application.getTermsHash(),
                        application.getBorrowerId(),
                        application.getApplicationCode(),
                        application.getPurposeCode().name()
                )
        );
    }
}
