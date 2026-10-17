package com.finora.payment.config;

import com.finora.common.observability.OperationalGauge;
import com.finora.common.observability.OperationalGaugeSet;
import com.finora.payment.repository.operations.PaymentOperationalMetricsRepository;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gauge vận hành của Payment (W3E, D58): outbox và bốn hàng đợi đối soát tiền (trả nợ, giải ngân, nạp tiền, rút tiền W3B).
 * Tên metric và cách xử lý từng hàng đợi nằm trong {@code docs/ops/RUNBOOKS.md} (mục 4.1, 4.2, 4.4).
 */
@Configuration
public class PaymentOperationsConfig {

    @Bean
    OperationalGaugeSet paymentOperationalGauges(PaymentOperationalMetricsRepository repository) {
        return new OperationalGaugeSet("payment", List.of(
                OperationalGauge.outboxEvents("pending", repository::pendingOutboxEvents),
                OperationalGauge.outboxEvents("dead_letter", repository::deadLetterOutboxEvents),
                OperationalGauge.outboxOldestPendingAge(repository::oldestUnpublishedOutboxAgeSeconds),
                OperationalGauge.incidents("repayment_reconciliation_required",
                        repository::repaymentsReconciliationRequired),
                OperationalGauge.incidents("disbursement_reconciliation_required",
                        repository::disbursementsReconciliationRequired),
                OperationalGauge.incidents("topup_reconciliation_required",
                        repository::topUpsReconciliationRequired),
                OperationalGauge.incidents("withdrawal_reconciliation_required",
                        repository::withdrawalsReconciliationRequired)));
    }
}
