package com.finora.loan.service;

import com.finora.loan.domain.BorrowerEligibilityCheck;
import com.finora.loan.domain.LoanApplication;
import com.finora.loan.integration.ai.AiCreditIntegrationException;
import com.finora.loan.integration.ai.AiCreditScoreResponse;
import com.finora.loan.integration.ai.AiCreditScoringGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreditScoringOrchestrator {

    private final CreditScoringStateService stateService;
    private final BorrowerEligibilityService eligibilityService;
    private final AiCreditScoringGateway aiGateway;

    /** Mỗi external call nằm giữa hai transaction local; restart luôn tiếp tục được từ state đã commit. */
    public void processApplication(Long applicationId) {
        LoanApplication application = stateService.startEligibility(applicationId);
        BorrowerEligibilityCheck eligibility = eligibilityService.evaluate(
                application.getId(), application.getBorrowerId());
        Long assessmentId = stateService.applyEligibility(eligibility);
        if (assessmentId != null) {
            executeAssessment(assessmentId);
        }
    }

    public void executeAssessment(Long assessmentId) {
        CreditScoringExecution execution = stateService.startExecution(assessmentId);
        if (!execution.executionRequired()) {
            return;
        }
        try {
            AiCreditScoreResponse response = aiGateway.score(execution.request(), execution.requestId());
            stateService.complete(assessmentId, response);
            log.info("Chấm điểm tín dụng thành công: assessmentId={}, requestId={}, modelVersion={}",
                    assessmentId, execution.requestId(), response.modelVersion());
        } catch (AiCreditIntegrationException failure) {
            stateService.fail(assessmentId, failure);
            log.warn("Chấm điểm tín dụng chưa thành công: assessmentId={}, requestId={}, code={}, retryable={}",
                    assessmentId, execution.requestId(), failure.getCode(), failure.isRetryable());
        }
    }
}
