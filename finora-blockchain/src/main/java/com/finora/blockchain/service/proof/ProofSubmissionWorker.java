package com.finora.blockchain.service.proof;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker opt-in; mặc định tắt để không tạo bằng chứng giả khi chưa có contract P4. */
@Component
@ConditionalOnProperty(name = "finora.blockchain.proof.worker-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ProofSubmissionWorker {

    private final ProofSubmissionStateService stateService;
    private final ProofSubmissionProcessor processor;

    @Scheduled(fixedDelayString = "${finora.blockchain.proof.worker-delay:5000}")
    public void processDueProofs() {
        for (Long id : stateService.dueIds()) {
            try {
                processor.process(id);
            } catch (RuntimeException failure) {
                log.error("Proof worker chưa xử lý được submission: databaseId={}, exceptionType={}",
                        id, failure.getClass().getName());
            }
        }
    }
}
