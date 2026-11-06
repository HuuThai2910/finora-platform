package com.finora.loan.service.core.impl;

import com.finora.loan.config.FineractProperties;
import com.finora.loan.service.core.CoreProductSyncRetryWorker;
import com.finora.loan.service.core.CoreProductSyncService;
import com.finora.loan.service.core.CoreProductSyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker định kỳ thực thi lại các command đồng bộ Product sang Fineract đã đến hạn.
 *
 * <p>Quét một batch nhỏ command đến hạn. Fineract chưa có batch command tương đương nên
 * worker gọi tuần tự có giới hạn, tách hoàn toàn khỏi request người dùng.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoreProductSyncRetryWorkerImpl implements CoreProductSyncRetryWorker {

    private final CoreProductSyncStateService coreProductSyncStateService;
    private final CoreProductSyncService coreProductSyncService;
    private final FineractProperties fineractProperties;

    @Override
    @Scheduled(fixedDelayString = "${finora.fineract.retry-worker-delay:10000}")
    public void retryDueCommands() {
        for (String commandId : coreProductSyncStateService.findDueCommandIds(fineractProperties.retryBatchSize())) {
            try {
                coreProductSyncService.execute(commandId);
            } catch (RuntimeException failure) {
                log.error("Retry worker không xử lý được Fineract command: commandId={}, exceptionType={}",
                        commandId, failure.getClass().getName());
            }
        }
    }
}
