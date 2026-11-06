package com.finora.loan.service.restructure.impl;

import com.finora.loan.service.restructure.LoanRescheduleExecutionService;
import com.finora.loan.service.restructure.LoanRescheduleWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker định kỳ chạy các yêu cầu cơ cấu đến hạn trên Fineract (bật bằng {@code finora.loan.reschedule.worker-enabled}).
 * <p>
 * Lỗi của một yêu cầu chỉ được log, không chặn các yêu cầu sau trong cùng vòng.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "finora.loan.reschedule", name = "worker-enabled", havingValue = "true")
public class LoanRescheduleWorkerImpl implements LoanRescheduleWorker {
    private final LoanRescheduleExecutionService loanRescheduleExecutionService;

    @Override
    @Scheduled(fixedDelayString = "${finora.loan.reschedule.worker-delay:5000}")
    public void run() {
        loanRescheduleExecutionService.dueIds().forEach(id -> {
            try {
                loanRescheduleExecutionService.execute(id);
            } catch (RuntimeException exception) {
                log.error("Không xử lý được yêu cầu cơ cấu: requestDbId={}", id, exception);
            }
        });
    }
}
