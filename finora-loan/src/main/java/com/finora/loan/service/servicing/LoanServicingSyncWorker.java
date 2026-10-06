package com.finora.loan.service.servicing;

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
            catch (RuntimeException exception) {
                log.error("Không đồng bộ được servicing từ Fineract: loanId={}", id, exception);
            }
        });
    }
}
