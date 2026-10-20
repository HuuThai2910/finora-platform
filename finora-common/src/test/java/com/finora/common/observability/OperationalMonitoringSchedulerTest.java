package com.finora.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OperationalMonitoringSchedulerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private OperationalMonitoringScheduler scheduler;

    @AfterEach
    void stopScheduler() {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    @Test
    void gaugeIsNaNUntilFirstReadThenShowsTheCountWithServiceTag() {
        AtomicReference<Double> pending = new AtomicReference<>(3.0);
        scheduler = scheduler(OperationalMetricsProperties.defaults(),
                List.of(new OperationalGaugeSet("loan", List.of(
                        OperationalGauge.outboxEvents("pending", pending::get)))),
                List.of());

        scheduler.start();
        Gauge gauge = registry.get(OperationalGauge.OUTBOX_EVENTS).tag("service", "loan").tag("status", "pending")
                .gauge();
        // Chưa đọc lần nào thì chưa biết, không được báo 0 như thể hàng đợi trống.
        assertThat(gauge.value()).isNaN();

        scheduler.refreshNow();
        assertThat(gauge.value()).isEqualTo(3.0);

        pending.set(0.0);
        scheduler.refreshNow();
        assertThat(gauge.value()).isZero();
    }

    @Test
    void failedReadReportsNaNCountsTheFailureAndRecoversOnNextRead() {
        AtomicInteger calls = new AtomicInteger();
        scheduler = scheduler(OperationalMetricsProperties.defaults(),
                List.of(new OperationalGaugeSet("payment", List.of(
                        OperationalGauge.incidents("repayment_reconciliation_required", () -> {
                            if (calls.incrementAndGet() == 2) {
                                throw new IllegalStateException("database tạm mất kết nối");
                            }
                            return 7;
                        })))),
                List.of());

        scheduler.refreshNow();
        Gauge gauge = registry.get(OperationalGauge.INCIDENTS).tag("type", "repayment_reconciliation_required").gauge();
        assertThat(gauge.value()).isEqualTo(7.0);

        scheduler.refreshNow();
        assertThat(gauge.value()).isNaN();
        assertThat(registry.get(OperationalMonitoringScheduler.GAUGE_REFRESH_FAILURES)
                .tag("service", "payment").tag("metric", OperationalGauge.INCIDENTS).counter().count())
                .isEqualTo(1.0);

        scheduler.refreshNow();
        assertThat(gauge.value()).isEqualTo(7.0);
    }

    @Test
    void taskRunsBeforeGaugesSoGaugesReadTheTaskSnapshot() {
        AtomicReference<Double> snapshot = new AtomicReference<>(Double.NaN);
        OperationalRefreshTask task = new OperationalRefreshTask() {
            @Override
            public String name() {
                return "fineract-eod";
            }

            @Override
            public Duration interval() {
                return Duration.ofMinutes(5);
            }

            @Override
            public void refresh() {
                snapshot.set(42.0);
            }
        };
        scheduler = scheduler(OperationalMetricsProperties.defaults(),
                List.of(new OperationalGaugeSet("loan", List.of(new OperationalGauge("finora.test.age",
                        "tuổi thử", "seconds", java.util.Map.of(), snapshot::get)))),
                List.of(task));

        scheduler.refreshNow();

        assertThat(registry.get("finora.test.age").gauge().value()).isEqualTo(42.0);
    }

    @Test
    void failingTaskIsCountedAndDoesNotStopGauges() {
        OperationalRefreshTask task = new OperationalRefreshTask() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public Duration interval() {
                return Duration.ofMinutes(1);
            }

            @Override
            public void refresh() {
                throw new IllegalStateException("dependency lỗi");
            }
        };
        scheduler = scheduler(OperationalMetricsProperties.defaults(),
                List.of(new OperationalGaugeSet("loan", List.of(OperationalGauge.outboxEvents("dead_letter", () -> 1)))),
                List.of(task));

        scheduler.refreshNow();

        assertThat(registry.get(OperationalMonitoringScheduler.TASK_FAILURES).tag("task", "broken").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(OperationalGauge.OUTBOX_EVENTS).tag("status", "dead_letter").gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    void duplicateNameAndTagsInOneServiceIsRejected() {
        scheduler = scheduler(OperationalMetricsProperties.defaults(),
                List.of(new OperationalGaugeSet("loan", List.of(
                        OperationalGauge.outboxEvents("pending", () -> 1),
                        OperationalGauge.outboxEvents("pending", () -> 2)))),
                List.of());

        assertThatThrownBy(scheduler::refreshNow)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OperationalGauge.OUTBOX_EVENTS);
    }

    @Test
    void disabledSchedulerRegistersNothing() {
        OperationalMetricsProperties disabled = new OperationalMetricsProperties(false, Duration.ofSeconds(60),
                Duration.ZERO, 10_000, Duration.ofSeconds(5));
        scheduler = scheduler(disabled,
                List.of(new OperationalGaugeSet("loan", List.of(OperationalGauge.outboxEvents("pending", () -> 1)))),
                List.of());

        scheduler.start();
        scheduler.refreshNow();

        assertThat(registry.find(OperationalGauge.OUTBOX_EVENTS).gauge()).isNull();
        assertThat(scheduler.isRunning()).isTrue();
    }

    @Test
    void startedSchedulerRefreshesOnItsOwnThread() {
        AtomicInteger reads = new AtomicInteger();
        OperationalMetricsProperties fast = new OperationalMetricsProperties(true, Duration.ofMillis(50),
                Duration.ofMillis(10), 10_000, Duration.ofSeconds(5));
        scheduler = scheduler(fast,
                List.of(new OperationalGaugeSet("investment", List.of(OperationalGauge.incidents("order_release_blocked",
                        () -> {
                            reads.incrementAndGet();
                            return 2;
                        })))),
                List.of());

        scheduler.start();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(reads.get()).isGreaterThanOrEqualTo(2);
            assertThat(registry.get(OperationalGauge.INCIDENTS).tag("service", "investment").gauge().value())
                    .isEqualTo(2.0);
        });
        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void propertiesRejectSubSecondQueryTimeoutBecauseJdbcRoundsItToUnlimited() {
        assertThatThrownBy(() -> new OperationalMetricsProperties(true, Duration.ofSeconds(60), Duration.ZERO,
                10_000, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryTimeout");
        assertThatThrownBy(() -> new OperationalMetricsProperties(true, Duration.ofSeconds(60), Duration.ZERO,
                0, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("countCap");
        assertThat(OperationalMetricsProperties.defaults().queryTimeoutSeconds()).isEqualTo(5);
    }

    private OperationalMonitoringScheduler scheduler(OperationalMetricsProperties properties,
            List<OperationalGaugeSet> sets, List<OperationalRefreshTask> tasks) {
        return new OperationalMonitoringScheduler(registry, properties, sets, tasks);
    }
}
