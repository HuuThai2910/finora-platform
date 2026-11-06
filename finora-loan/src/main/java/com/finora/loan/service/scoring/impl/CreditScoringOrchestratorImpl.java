package com.finora.loan.service.scoring.impl;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.scoring.entity.BorrowerEligibilityCheck;
import com.finora.loan.integration.ai.AiCreditIntegrationException;
import com.finora.loan.integration.ai.client.AiCreditScoringGateway;
import com.finora.loan.integration.ai.contract.AiCreditScoreResponse;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.client.FineractScheduleGateway;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;
import com.finora.loan.service.scoring.BorrowerEligibilityService;
import com.finora.loan.service.scoring.CreditScoringExecution;
import com.finora.loan.service.scoring.CreditScoringFinalization;
import com.finora.loan.service.scoring.CreditScoringOrchestrator;
import com.finora.loan.service.scoring.CreditScoringStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Điều phối chấm điểm tín dụng một hồ sơ: xét điều kiện người vay, gọi AI rồi tính lịch trả nợ cuối ở Fineract.
 * <p>
 * Mỗi lời gọi ngoài nằm giữa hai transaction local của {@link CreditScoringStateService}; lỗi AI hoặc Fineract được ghi
 * thành trạng thái chờ thử lại hoặc chờ duyệt tay.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditScoringOrchestratorImpl implements CreditScoringOrchestrator {

    private final CreditScoringStateService creditScoringStateService;
    private final BorrowerEligibilityService borrowerEligibilityService;
    private final AiCreditScoringGateway aiCreditScoringGateway;
    private final FineractScheduleGateway fineractScheduleGateway;

    @Override
    public void processApplication(Long applicationId) {
        LoanApplication application = creditScoringStateService.startEligibility(applicationId);
        BorrowerEligibilityCheck eligibility = borrowerEligibilityService.evaluate(
                application.getId(), application.getBorrowerId(), application.getKycSnapshot());
        Long assessmentId = creditScoringStateService.applyEligibility(eligibility);
        if (assessmentId != null) {
            executeAssessment(assessmentId);
        }
    }

    @Override
    public void executeAssessment(Long assessmentId) {
        CreditScoringExecution execution = creditScoringStateService.startExecution(assessmentId);
        if (!execution.executionRequired()) {
            return;
        }
        try {
            AiCreditScoreResponse response = aiCreditScoringGateway.score(execution.request(), execution.requestId());
            CreditScoringFinalization finalization =
                    creditScoringStateService.prepareFinalization(assessmentId, response);
            ScheduleCalculationResult finalSchedule = finalization.scheduleRequired()
                    ? fineractScheduleGateway.calculateSchedule(finalization.scheduleRequest())
                    : null;
            creditScoringStateService.complete(assessmentId, response, finalization, finalSchedule);
            log.info("Chấm điểm tín dụng thành công: assessmentId={}, requestId={}, modelVersion={}",
                    assessmentId, execution.requestId(), response.modelVersion());
        } catch (AiCreditIntegrationException failure) {
            creditScoringStateService.fail(assessmentId, failure);
            log.warn("Chấm điểm tín dụng chưa thành công: assessmentId={}, requestId={}, code={}, retryable={}",
                    assessmentId, execution.requestId(), failure.getCode(), failure.isRetryable());
        } catch (FineractIntegrationException failure) {
            creditScoringStateService.fail(
                    assessmentId,
                    "FINERACT_FINAL_SCHEDULE_" + failure.getCode(),
                    failure.getMessage(),
                    failure.isRetryable()
            );
            log.warn("Chưa tính được lịch trả nợ cuối: assessmentId={}, requestId={}, code={}, retryable={}",
                    assessmentId, execution.requestId(), failure.getCode(), failure.isRetryable());
        }
    }
}
