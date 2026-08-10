package com.finora.loan.service.scoring;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "finora.ai.credit.worker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CreditScoringWorker {

    private final CreditScoringStateService stateService;
    private final CreditScoringOrchestrator orchestrator;

    /** Batch hữu hạn, không gọi AI theo từng dòng của một API list và không giữ transaction khi chạy vòng lặp. */
    @Scheduled(fixedDelayString = "${finora.ai.credit.worker-delay:5000}")
    public void processDueScoring() {
        for (Long applicationId : stateService.findApplicationCandidates()) {
            try {
                orchestrator.processApplication(applicationId);
            } catch (RuntimeException failure) {
                log.error("Scoring worker chưa xử lý được eligibility: applicationId={}, exceptionType={}",
                        applicationId, failure.getClass().getName());
            }
        }
        for (Long assessmentId : stateService.findAssessmentCandidates()) {
            try {
                orchestrator.executeAssessment(assessmentId);
            } catch (RuntimeException failure) {
                log.error("Scoring worker chưa xử lý được assessment: assessmentId={}, exceptionType={}",
                        assessmentId, failure.getClass().getName());
            }
        }
    }
}
