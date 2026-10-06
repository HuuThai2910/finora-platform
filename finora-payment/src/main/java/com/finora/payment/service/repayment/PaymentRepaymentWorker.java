package com.finora.payment.service.repayment;

import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "finora.payment", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRepaymentWorker {
    private final PaymentRepaymentService service;
    private final Clock clock;
    private final Environment environment;

    @Scheduled(fixedDelayString = "${finora.payment.repayment-worker-delay:3000}")
    public void run() {
        service.collectedIds().forEach(id -> safely(id, () -> service.postToCore(id), "core-posting"));
        service.corePostedIds().forEach(id -> safely(id, () -> service.distribute(id), "distribution"));
    }

    @Scheduled(fixedDelayString = "${finora.payment.reconciliation-worker-delay:30000}")
    public void reconcile() {
        Duration staleAfter = environment.getProperty(
                "finora.payment.core-posting-stale-after", Duration.class, Duration.ofMinutes(1));
        service.staleCorePostingIds(clock.instant().minus(staleAfter)).forEach(id ->
                safely(id, () -> service.recoverStaleCorePosting(id), "recover-stale-core-posting"));
        service.reconciliationRequiredIds().forEach(id ->
                safely(id, () -> service.reconcileCorePosting(id), "core-reconciliation"));
    }

    private void safely(Long id, Runnable action, String step) {
        try { action.run(); }
        catch (RuntimeException exception) {
            log.error("Repayment worker thất bại: repaymentId={}, step={}", id, step, exception);
        }
    }
}
