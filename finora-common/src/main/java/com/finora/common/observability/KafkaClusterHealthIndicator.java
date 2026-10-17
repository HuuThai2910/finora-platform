package com.finora.common.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Health {@code kafka}: broker có trả lời {@code describeCluster} trong thời gian cho phép không (W3E, D56).
 *
 * <p>Indicator này cố ý KHÔNG nằm trong nhóm readiness. Service ghi event vào transactional outbox trong database, nên
 * Kafka tắt chỉ làm event đến muộn chứ không làm request hỏng; đưa Kafka vào readiness sẽ khiến mọi service (dùng chung
 * một broker) cùng bị rút khỏi traffic, biến sự cố trễ event thành sự cố toàn hệ thống. Kafka vẫn hiện ở
 * {@code /actuator/health} tổng và độ trễ thật nằm ở gauge {@code finora.outbox.oldest.pending.age}.</p>
 *
 * <p>Mỗi lần kiểm tạo một admin client ngắn hạn rồi đóng: client dùng lâu sẽ tự kết nối lại liên tục khi broker tắt.
 * Kết quả được cache {@code cacheTtl} để endpoint health bị gọi dồn không tạo nhiều client; khi broker tắt, mỗi lần kiểm
 * tốn tối đa {@code timeout}.</p>
 */
public class KafkaClusterHealthIndicator implements HealthIndicator {

    private final Supplier<Admin> adminFactory;
    private final Duration timeout;
    private final Duration cacheTtl;
    private final Clock clock;
    private volatile Cached cached;

    public KafkaClusterHealthIndicator(Supplier<Admin> adminFactory, Duration timeout, Duration cacheTtl, Clock clock) {
        this.adminFactory = Objects.requireNonNull(adminFactory, "adminFactory");
        this.timeout = requirePositive(timeout, "timeout");
        this.cacheTtl = cacheTtl == null || cacheTtl.isNegative() ? Duration.ZERO : cacheTtl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Health health() {
        Cached current = cached;
        if (current != null && clock.instant().isBefore(current.expiresAt())) {
            return current.health();
        }
        return refresh();
    }

    private synchronized Health refresh() {
        Cached current = cached;
        Instant now = clock.instant();
        // Nhiều request cùng hết cache chỉ một request đi kiểm, các request còn lại đọc kết quả vừa có.
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.health();
        }
        Health health = check();
        cached = new Cached(health, now.plus(cacheTtl));
        return health;
    }

    private Health check() {
        Admin admin;
        try {
            admin = adminFactory.get();
        } catch (RuntimeException exception) {
            return down(exception);
        }
        try {
            DescribeClusterResult result = admin.describeCluster(
                    new DescribeClusterOptions().timeoutMs(Math.toIntExact(timeout.toMillis())));
            Collection<Node> nodes = result.nodes().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            String clusterId = result.clusterId().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (nodes == null || nodes.isEmpty()) {
                return Health.down().withDetail("error", "NO_BROKER").build();
            }
            return Health.up()
                    .withDetail("clusterId", clusterId == null ? "unknown" : clusterId)
                    .withDetail("brokers", nodes.size())
                    .build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("error", "INTERRUPTED").build();
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            // Indicator không kế thừa AbstractHealthIndicator: exception lọt ra sẽ làm cả endpoint health lỗi 500.
            return down(exception);
        } finally {
            // close() không tham số chờ vô hạn các lời gọi còn dở khi broker tắt.
            admin.close(timeout);
        }
    }

    /** Chỉ trả loại lỗi gốc; message của client Kafka có thể kèm địa chỉ nội bộ, không cần cho người đọc health. */
    private static Health down(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return Health.down().withDetail("error", root.getClass().getSimpleName()).build();
    }

    private static Duration requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Kafka health " + field + " phải lớn hơn 0");
        }
        return value;
    }

    private record Cached(Health health, Instant expiresAt) {
    }
}
