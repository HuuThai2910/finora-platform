package com.finora.loan.config;

import com.finora.common.observability.OperationalGauge;
import com.finora.common.observability.OperationalGaugeSet;
import com.finora.loan.repository.operations.LoanOperationalMetricsRepository;
import com.finora.loan.service.operations.FineractEndOfDayMonitor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gauge vận hành của Loan (W3E, D58) và cấu hình giám sát job cuối ngày Fineract (D57). Tên metric và ý nghĩa tag nằm
 * trong {@code docs/ops/RUNBOOKS.md}; giá trị được làm mới trên luồng vận hành của finora-common, không chạy khi scrape.
 */
@Configuration
@EnableConfigurationProperties(FineractEndOfDayProperties.class)
public class LoanOperationsConfig {

    /** Tuổi (giây) lần chạy thành công gần nhất của từng job cuối ngày Fineract; NaN khi chưa biết/không áp dụng. */
    public static final String FINERACT_EOD_LAST_SUCCESS_AGE = "finora.fineract.eod.last.success.age";

    @Bean
    OperationalGaugeSet loanOperationalGauges(
            LoanOperationalMetricsRepository repository,
            FineractEndOfDayMonitor endOfDayMonitor,
            FineractEndOfDayProperties endOfDay
    ) {
        List<OperationalGauge> gauges = new ArrayList<>(List.of(
                OperationalGauge.outboxEvents("pending", repository::pendingOutboxEvents),
                OperationalGauge.outboxEvents("dead_letter", repository::deadLetterOutboxEvents),
                OperationalGauge.outboxOldestPendingAge(repository::oldestUnpublishedOutboxAgeSeconds),
                OperationalGauge.incidents("disbursement_saga_repair_required",
                        repository::disbursementSagasRepairRequired),
                OperationalGauge.incidents("servicing_reconciliation_open",
                        repository::openServicingReconciliationIncidents),
                OperationalGauge.incidents("repayment_event_quarantined",
                        repository::pendingRepaymentEventQuarantine)));
        for (String job : endOfDay.jobs()) {
            gauges.add(new OperationalGauge(FINERACT_EOD_LAST_SUCCESS_AGE,
                    "Số giây kể từ lần chạy thành công gần nhất của job cuối ngày Fineract",
                    "seconds", Map.of("job", job), () -> endOfDayMonitor.lastSuccessAgeSeconds(job)));
        }
        return new OperationalGaugeSet("loan", gauges);
    }
}
