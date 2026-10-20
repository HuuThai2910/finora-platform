package com.finora.loan.service.servicing;

import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.fineract.client.FineractIntegrationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "finora.loan.servicing-sync", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class LoanServicingSyncWorker {
    private final LoanServicingSyncService service;

    @Scheduled(fixedDelayString = "${finora.loan.servicing-sync.worker-delay:60000}")
    public void run() {
        service.dueIds(20).forEach(id -> {
            try { service.sync(id); }
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
