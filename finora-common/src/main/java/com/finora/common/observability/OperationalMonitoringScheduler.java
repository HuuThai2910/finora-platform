package com.finora.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Đăng ký gauge vận hành và làm mới chúng định kỳ (W3E, D58).
 *
 * <p>Vì sao không để gauge tự truy vấn khi scrape: Micrometer gọi hàm gauge mỗi lần có người đọc metric, nên câu SQL sẽ
 * chạy theo nhịp scrape (thường 15 giây, nhiều bản sao) và một database chậm làm treo luôn endpoint metric. Ở đây giá
 * trị được tính trên một luồng daemon riêng theo {@code refreshInterval}; scrape chỉ đọc số đã có. Luồng riêng (không
 * dùng {@code @Scheduled}) để câu đếm hoặc lời gọi Fineract chậm không chiếm scheduler một luồng của worker nghiệp vụ
 * (outbox, saga), và không khai báo bean {@code TaskScheduler} nào làm thay scheduler mặc định của service.</p>
 *
 * <p>Đọc lỗi thì gauge báo NaN (chưa biết) thay vì giữ số cũ: số 0 cũ khi database đã mất có thể bị hiểu là "không có
 * sự cố". Mỗi lần lỗi tăng {@value #GAUGE_REFRESH_FAILURES} hoặc {@value #TASK_FAILURES}; log WARN một lần cho mỗi đợt
 * lỗi liên tiếp và INFO khi hồi phục. Hai counter tách tên vì Prometheus đòi mọi series cùng tên có cùng bộ tag.</p>
 */
@Slf4j
public class OperationalMonitoringScheduler implements SmartLifecycle {

    static final String GAUGE_REFRESH_FAILURES = "finora.operations.gauge.refresh.failures";
    static final String TASK_FAILURES = "finora.operations.task.failures";
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final MeterRegistry registry;
    private final OperationalMetricsProperties properties;
    private final List<OperationalGaugeSet> gaugeSets;
    private final List<OperationalRefreshTask> tasks;
    private final List<RegisteredGauge> gauges = new ArrayList<>();
    private final List<RegisteredTask> registeredTasks = new ArrayList<>();
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public OperationalMonitoringScheduler(
            MeterRegistry registry,
            OperationalMetricsProperties properties,
            List<OperationalGaugeSet> gaugeSets,
            List<OperationalRefreshTask> tasks
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.gaugeSets = List.copyOf(gaugeSets);
        this.tasks = List.copyOf(tasks);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        if (!properties.enabled()) {
            log.info("Gauge vận hành đang tắt (finora.operations.metrics.enabled=false)");
            return;
        }
        if (gauges.isEmpty() && registeredTasks.isEmpty()) {
            registerAll();
        }
        if (gauges.isEmpty() && registeredTasks.isEmpty()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "finora-ops-refresh");
            thread.setDaemon(true);
            return thread;
        });
        long initialDelay = properties.initialDelay().toMillis();
        // Task (ví dụ kiểm tra EOD Fineract) xếp trước gauge để lần đếm đầu đã có trạng thái task mới nhất.
        for (RegisteredTask task : registeredTasks) {
            executor.scheduleWithFixedDelay(task::run, initialDelay, task.intervalMillis(), TimeUnit.MILLISECONDS);
        }
        if (!gauges.isEmpty()) {
            executor.scheduleWithFixedDelay(this::refreshGauges, initialDelay,
                    properties.refreshInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
        log.info("Bật gauge vận hành: gauges={}, tasks={}, refreshInterval={}",
                gauges.size(), registeredTasks.size(), properties.refreshInterval());
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Luồng làm mới gauge vận hành chưa dừng sau {} giây", STOP_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Chạy ngay mọi task rồi làm mới mọi gauge trên luồng gọi. Dùng cho test và cho lần đọc cần số mới nhất; không
     * thay lịch định kỳ. Khi {@code enabled=false} không làm gì.
     */
    public synchronized void refreshNow() {
        if (!properties.enabled()) {
            return;
        }
        if (gauges.isEmpty() && registeredTasks.isEmpty()) {
            registerAll();
        }
        registeredTasks.forEach(RegisteredTask::run);
        refreshGauges();
    }

    private void registerAll() {
        Set<String> seen = new HashSet<>();
        for (OperationalGaugeSet set : gaugeSets) {
            for (OperationalGauge gauge : set.gauges()) {
                Map<String, String> tags = gauge.tagsWithService(set.service());
                if (!seen.add(gauge.name() + tags)) {
                    throw new IllegalStateException("Gauge vận hành trùng tên và tag: " + gauge.name() + tags);
                }
                gauges.add(new RegisteredGauge(set.service(), gauge, toTags(tags), registry));
            }
        }
        for (OperationalRefreshTask task : tasks) {
            registeredTasks.add(new RegisteredTask(task, registry));
        }
    }

    private void refreshGauges() {
        for (RegisteredGauge gauge : gauges) {
            gauge.refresh();
        }
    }

    private static Tags toTags(Map<String, String> tags) {
        Tags result = Tags.empty();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            result = result.and(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** Giữ giá trị mới nhất của một gauge; Micrometer đọc {@link #value()} khi scrape. */
    private static final class RegisteredGauge {
        private final String service;
        private final OperationalGauge gauge;
        private final Tags tags;
        private final Counter failures;
        private volatile double value = Double.NaN;
        // Chỉ phục vụ log một lần mỗi đợt lỗi; luồng lịch và refreshNow có thể cùng ghi nên dùng volatile.
        private volatile boolean failing;

        RegisteredGauge(String service, OperationalGauge gauge, Tags tags, MeterRegistry registry) {
            this.service = service;
            this.gauge = gauge;
            this.tags = tags;
            // strongReference: gauge của Micrometer chỉ giữ tham chiếu yếu tới object; holder này phải sống cùng registry.
            Gauge.builder(gauge.name(), this, RegisteredGauge::value)
                    .description(gauge.description())
                    .baseUnit(gauge.baseUnit())
                    .tags(tags)
                    .strongReference(true)
                    .register(registry);
            // Gộp theo (service, metric): các biến thể tag của cùng metric dùng chung một counter.
            this.failures = Counter.builder(GAUGE_REFRESH_FAILURES)
                    .description("Số lần đọc gauge vận hành bị lỗi")
                    .tags("service", service, "metric", gauge.name())
                    .register(registry);
        }

        double value() {
            return value;
        }

        void refresh() {
            try {
                value = gauge.reader().getAsDouble();
                if (failing) {
                    failing = false;
                    log.info("Gauge vận hành đọc lại được: service={}, metric={}, tags={}", service, gauge.name(), tags);
                }
            } catch (RuntimeException exception) {
                value = Double.NaN;
                failures.increment();
                if (!failing) {
                    failing = true;
                    log.warn("Không đọc được gauge vận hành, báo NaN tới lần đọc được: service={}, metric={}, tags={}, "
                            + "errorType={}", service, gauge.name(), tags, exception.getClass().getSimpleName());
                }
            }
        }
    }

    private static final class RegisteredTask {
        private final OperationalRefreshTask task;
        private final Counter failures;
        private volatile boolean failing;

        RegisteredTask(OperationalRefreshTask task, MeterRegistry registry) {
            this.task = task;
            if (task.interval() == null || task.interval().isZero() || task.interval().isNegative()) {
                throw new IllegalStateException("Task vận hành " + task.name() + " phải có interval lớn hơn 0");
            }
            this.failures = Counter.builder(TASK_FAILURES)
                    .description("Số lần task vận hành định kỳ bị lỗi")
                    .tags("task", task.name())
                    .register(registry);
        }

        long intervalMillis() {
            return task.interval().toMillis();
        }

        void run() {
            try {
                task.refresh();
                if (failing) {
                    failing = false;
                    log.info("Task vận hành chạy lại được: task={}", task.name());
                }
            } catch (RuntimeException exception) {
                failures.increment();
                if (!failing) {
                    failing = true;
                    log.warn("Task vận hành lỗi, thử lại ở lượt sau: task={}, errorType={}",
                            task.name(), exception.getClass().getSimpleName());
                }
            }
        }
    }
}
