package com.finora.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class KafkaClusterHealthIndicatorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T03:00:00Z"));
    private final AtomicInteger adminsCreated = new AtomicInteger();

    @Test
    void reachableBrokerIsUpWithClusterIdAndBrokerCount() {
        Admin admin = adminReturning(KafkaFuture.completedFuture(List.of(new Node(1, "kafka", 29092))),
                KafkaFuture.completedFuture("cluster-a"));
        KafkaClusterHealthIndicator indicator = indicator(admin);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("clusterId", "cluster-a").containsEntry("brokers", 1);
        verify(admin).close(TIMEOUT);
    }

    @Test
    void unreachableBrokerIsDownWithOnlyTheErrorTypeAndTheClientIsClosed() {
        KafkaFutureImpl<java.util.Collection<Node>> failed = new KafkaFutureImpl<>();
        failed.completeExceptionally(new TimeoutException("Timed out waiting for a node assignment"));
        Admin admin = adminReturning(failed, KafkaFuture.completedFuture("unused"));
        KafkaClusterHealthIndicator indicator = indicator(admin);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        // Chỉ loại lỗi, không có message (có thể kèm địa chỉ broker nội bộ).
        assertThat(health.getDetails()).containsOnlyKeys("error").containsEntry("error", "TimeoutException");
        verify(admin).close(TIMEOUT);
    }

    @Test
    void resultIsCachedUntilTheTtlExpires() {
        Admin admin = adminReturning(KafkaFuture.completedFuture(List.of(new Node(1, "kafka", 29092))),
                KafkaFuture.completedFuture("cluster-a"));
        KafkaClusterHealthIndicator indicator = indicator(admin);

        indicator.health();
        clock.advance(CACHE_TTL.minusSeconds(1));
        indicator.health();
        assertThat(adminsCreated).hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        indicator.health();
        assertThat(adminsCreated).hasValue(2);
    }

    @Test
    void clientThatCannotBeCreatedOrFailsUnexpectedlyIsDownNotAnEndpointError() {
        KafkaClusterHealthIndicator brokenFactory = new KafkaClusterHealthIndicator(() -> {
            throw new org.apache.kafka.common.config.ConfigException("bootstrap.servers trống");
        }, TIMEOUT, CACHE_TTL, clock);
        assertThat(brokenFactory.health().getDetails()).containsEntry("error", "ConfigException");

        Admin admin = mock(Admin.class);
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenThrow(new IllegalStateException("closed"));
        Health health = indicator(admin).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "IllegalStateException");
        verify(admin).close(TIMEOUT);
    }

    @Test
    void noBrokerInMetadataIsDown() {
        Admin admin = adminReturning(KafkaFuture.completedFuture(List.of()), KafkaFuture.completedFuture("cluster-a"));

        Health health = indicator(admin).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "NO_BROKER");
    }

    private KafkaClusterHealthIndicator indicator(Admin admin) {
        return new KafkaClusterHealthIndicator(() -> {
            adminsCreated.incrementAndGet();
            return admin;
        }, TIMEOUT, CACHE_TTL, clock);
    }

    private static Admin adminReturning(KafkaFuture<java.util.Collection<Node>> nodes, KafkaFuture<String> clusterId) {
        DescribeClusterResult result = mock(DescribeClusterResult.class);
        when(result.nodes()).thenReturn(nodes);
        when(result.clusterId()).thenReturn(clusterId);
        Admin admin = mock(Admin.class);
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(result);
        return admin;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
