package com.finora.payment.service.withdrawal.impl;

import com.finora.payment.service.withdrawal.WithdrawalProcessingService;
import com.finora.payment.service.withdrawal.WithdrawalWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker định kỳ đưa lệnh rút tới kết quả cuối (bật bằng {@code finora.payment.scheduling-enabled}).
 * <p>
 * Mỗi vòng chạy theo {@code finora.payment.worker-delay}; lỗi một lệnh chỉ ghi log, vòng sau thử lại.
 */
@Component
@ConditionalOnProperty(prefix = "finora.payment", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class WithdrawalWorkerImpl implements WithdrawalWorker {

    private final WithdrawalProcessingService withdrawalProcessingService;

    @Override
    @Scheduled(fixedDelayString = "${finora.payment.worker-delay:3000}")
    public void run() {
        for (Long id : withdrawalProcessingService.dueIds()) {
            try {
                withdrawalProcessingService.execute(id);
            } catch (RuntimeException exception) {
                // Một lệnh lỗi không được chặn các lệnh còn lại; worker thử lại ở vòng sau.
                log.error("Withdrawal worker thất bại: withdrawalId(db)={}", id, exception);
            }
        }
    }
}
