package com.finora.loan.service.core;

import com.finora.loan.config.FineractProperties;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Quét một batch nhỏ command đến hạn. Fineract chưa có batch command tương đương nên
 * worker gọi tuần tự có giới hạn, tách hoàn toàn khỏi request người dùng.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoreProductSyncRetryWorker {

    private final CoreProductSyncStateService stateService;
    private final CoreProductSyncService syncService;
    private final FineractProperties properties;

    @Scheduled(fixedDelayString = "${finora.fineract.retry-worker-delay:10000}")
    public void retryDueCommands() {
        for (String commandId : stateService.findDueCommandIds(properties.retryBatchSize())) {
            try {
                syncService.execute(commandId);
            } catch (RuntimeException failure) {
                log.error("Retry worker không xử lý được Fineract command: commandId={}, exceptionType={}",
                        commandId, failure.getClass().getName());
            }
        }
    }
}
