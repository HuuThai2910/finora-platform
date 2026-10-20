package com.finora.payment.service.withdrawal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "finora.payment", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class WithdrawalWorker {

    private final WithdrawalProcessingService service;

    @Scheduled(fixedDelayString = "${finora.payment.worker-delay:3000}")
    public void run() {
        for (Long id : service.dueIds()) {
            try {
                service.execute(id);
            } catch (RuntimeException exception) {
                // Một lệnh lỗi không được chặn các lệnh còn lại; worker thử lại ở vòng sau.
                log.error("Withdrawal worker thất bại: withdrawalId(db)={}", id, exception);
            }
        }
    }
}
