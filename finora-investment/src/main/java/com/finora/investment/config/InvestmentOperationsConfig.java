package com.finora.investment.config;

import com.finora.common.observability.OperationalGauge;
import com.finora.common.observability.OperationalGaugeSet;
import com.finora.investment.repository.operations.InvestmentOperationalMetricsRepository;
import com.finora.investment.service.OrderIncidentService;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gauge vận hành của Investment (W3E, D58): outbox và ba loại sự cố lệnh góp vốn ↔ khoản giữ Payment (R3, D30). Tổng
 * ba loại bằng hàng đợi màn "Sự cố lệnh góp vốn" của admin vì dùng cùng ngưỡng {@link OrderIncidentService#ATTEMPT_THRESHOLD}.
 */
@Configuration
public class InvestmentOperationsConfig {

    @Bean
    OperationalGaugeSet investmentOperationalGauges(InvestmentOperationalMetricsRepository repository) {
        int threshold = OrderIncidentService.ATTEMPT_THRESHOLD;
        return new OperationalGaugeSet("investment", List.of(
                OperationalGauge.outboxEvents("pending", repository::pendingOutboxEvents),
                OperationalGauge.outboxEvents("dead_letter", repository::deadLetterOutboxEvents),
                OperationalGauge.outboxOldestPendingAge(repository::oldestUnpublishedOutboxAgeSeconds),
                OperationalGauge.incidents("order_pending_funds_stuck",
                        () -> repository.ordersPendingFundsStuck(threshold)),
                OperationalGauge.incidents("order_release_retrying",
                        () -> repository.ordersReleaseRetrying(threshold)),
                OperationalGauge.incidents("order_release_blocked", repository::ordersReleaseBlocked)));
    }
}
