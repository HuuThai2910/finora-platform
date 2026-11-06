package com.finora.payment.service.repayment.impl;

import com.finora.payment.service.repayment.PaymentRepaymentService;
import com.finora.payment.service.repayment.PaymentRepaymentWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Worker định kỳ của luồng trả nợ (bật bằng {@code finora.payment.scheduling-enabled}).
 * <p>
 * {@code run} chạy theo {@code finora.payment.repayment-worker-delay}, {@code reconcile} theo
 * {@code finora.payment.reconciliation-worker-delay}; lỗi một khoản chỉ ghi log, vòng sau thử lại.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "finora.payment", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRepaymentWorkerImpl implements PaymentRepaymentWorker {
    private final PaymentRepaymentService paymentRepaymentService;
    private final Clock clock;
    private final Environment environment;

    @Override
    @Scheduled(fixedDelayString = "${finora.payment.repayment-worker-delay:3000}")
    public void run() {
        paymentRepaymentService.collectedIds().forEach(id ->
                runSafely(id, () -> paymentRepaymentService.postToCore(id), "core-posting"));
        paymentRepaymentService.corePostedIds().forEach(id ->
                runSafely(id, () -> paymentRepaymentService.distribute(id), "distribution"));
    }

    @Override
    @Scheduled(fixedDelayString = "${finora.payment.reconciliation-worker-delay:30000}")
    public void reconcile() {
        Duration staleAfter = environment.getProperty(
                "finora.payment.core-posting-stale-after", Duration.class, Duration.ofMinutes(1));
        paymentRepaymentService.staleCorePostingIds(clock.instant().minus(staleAfter)).forEach(id ->
                runSafely(id, () -> paymentRepaymentService.recoverStaleCorePosting(id), "recover-stale-core-posting"));
        paymentRepaymentService.reconciliationRequiredIds().forEach(id ->
                runSafely(id, () -> paymentRepaymentService.reconcileCorePosting(id), "core-reconciliation"));
    }

    private void runSafely(Long id, Runnable action, String step) {
        try { action.run(); }
        catch (RuntimeException exception) {
            log.error("Repayment worker thất bại: repaymentId={}, step={}", id, step, exception);
        }
    }
}
