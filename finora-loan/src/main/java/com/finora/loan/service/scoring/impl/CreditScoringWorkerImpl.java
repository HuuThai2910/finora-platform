package com.finora.loan.service.scoring.impl;

import com.finora.loan.service.scoring.CreditScoringOrchestrator;
import com.finora.loan.service.scoring.CreditScoringStateService;
import com.finora.loan.service.scoring.CreditScoringWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker định kỳ chấm điểm hồ sơ mới và assessment đến hạn (tắt bằng {@code finora.ai.credit.worker-enabled=false}).
 * <p>
 * Lỗi của một hồ sơ hoặc assessment chỉ được log, không chặn phần còn lại của batch.
 */
@Component
@ConditionalOnProperty(name = "finora.ai.credit.worker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CreditScoringWorkerImpl implements CreditScoringWorker {

    private final CreditScoringStateService creditScoringStateService;
    private final CreditScoringOrchestrator creditScoringOrchestrator;

    @Override
    @Scheduled(fixedDelayString = "${finora.ai.credit.worker-delay:5000}")
    public void processDueScoring() {
        for (Long applicationId : creditScoringStateService.findApplicationCandidates()) {
            try {
                creditScoringOrchestrator.processApplication(applicationId);
            } catch (RuntimeException failure) {
                log.error("Scoring worker chưa xử lý được eligibility: applicationId={}, exceptionType={}",
                        applicationId, failure.getClass().getName());
            }
        }
        for (Long assessmentId : creditScoringStateService.findAssessmentCandidates()) {
            try {
                creditScoringOrchestrator.executeAssessment(assessmentId);
            } catch (RuntimeException failure) {
                log.error("Scoring worker chưa xử lý được assessment: assessmentId={}, exceptionType={}",
                        assessmentId, failure.getClass().getName());
            }
        }
    }
}
