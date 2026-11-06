package com.finora.loan.service.servicing.impl;

import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.service.servicing.LoanServicingSyncService;
import com.finora.loan.service.servicing.LoanServicingSyncWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker định kỳ đồng bộ servicing từ Fineract (tắt bằng {@code finora.loan.servicing-sync.enabled=false}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "finora.loan.servicing-sync", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class LoanServicingSyncWorkerImpl implements LoanServicingSyncWorker {
    private final LoanServicingSyncService loanServicingSyncService;

    @Override
    @Scheduled(fixedDelayString = "${finora.loan.servicing-sync.worker-delay:60000}")
    public void run() {
        loanServicingSyncService.dueIds(20).forEach(id -> {
            try { loanServicingSyncService.sync(id); }
            catch (FineractIntegrationException exception) {
                // Lỗi tích hợp đã phân loại (ví dụ khoản vay không có trên Fineract của môi trường này): projection đã
                // được đánh dấu stale và xếp cuối hàng đợi; một dòng log có mã lỗi là đủ, không in stack trace mỗi vòng.
                log.warn("Không đồng bộ được servicing từ Fineract: loanId={}, code={}, retryable={}, message={}",
                        id, exception.getCode(), exception.isRetryable(), exception.getMessage());
            } catch (LoanBusinessException exception) {
                log.warn("Servicing cần đối soát: loanId={}, code={}, message={}",
                        id, exception.getCode(), exception.getMessage());
            } catch (RuntimeException exception) {
                log.error("Không đồng bộ được servicing từ Fineract: loanId={}", id, exception);
            }
        });
    }
}
