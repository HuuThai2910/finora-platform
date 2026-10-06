package com.finora.loan.service.restructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "finora.loan.reschedule", name = "worker-enabled", havingValue = "true")
public class LoanRescheduleWorker {
    private final LoanRescheduleExecutionService service;

    @Scheduled(fixedDelayString = "${finora.loan.reschedule.worker-delay:5000}")
    public void run() {
        service.dueIds().forEach(id -> {
            try {
                service.execute(id);
            } catch (RuntimeException exception) {
                log.error("Không xử lý được yêu cầu cơ cấu: requestDbId={}", id, exception);
            }
        });
    }
}
