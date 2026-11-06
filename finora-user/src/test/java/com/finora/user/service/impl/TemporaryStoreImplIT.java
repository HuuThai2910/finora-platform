package com.finora.user.service.impl;

import com.finora.user.service.TemporaryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kho tạm chạy trên PostgreSQL 17 thật (Testcontainers, container dùng một lần): hết hạn theo TTL, tăng bộ đếm
 * nguyên tử, mở cửa sổ mới sau khi hết hạn, ghi-nếu-chưa-có, dọn dòng hết hạn và ghi độc lập với transaction
 * của nơi gọi. Thời gian điều khiển bằng {@link MutableClock} thay cho {@code Clock} của service.
 */
@SpringBootTest(properties = "finora.temporary-store.cleanup-initial-delay-ms=3600000")
@Testcontainers
class TemporaryStoreImplIT {

    private static final Instant START = Instant.parse("2026-11-04T08:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_user_temporary_store")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired private TemporaryStore temporaryStore;
    @Autowired private MutableClock mutableClock;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager platformTransactionManager;

    private String prefix;

    @BeforeEach
    void setUp() {
        mutableClock.set(START);
        prefix = "it-" + UUID.randomUUID() + ":";
    }

    @Test
    void valueExpiresAfterTtl() {
        temporaryStore.put(prefix + "otp", "123456", Duration.ofSeconds(10));

        assertThat(temporaryStore.find(prefix + "otp")).contains("123456");
        assertThat(temporaryStore.exists(prefix + "otp")).isTrue();

        mutableClock.advance(Duration.ofSeconds(10));

        assertThat(temporaryStore.find(prefix + "otp")).isEmpty();
        assertThat(temporaryStore.exists(prefix + "otp")).isFalse();
    }

    @Test
    void putOverwritesValueAndRefreshesTtl() {
        temporaryStore.put(prefix + "draft", "v1", Duration.ofSeconds(10));
        mutableClock.advance(Duration.ofSeconds(8));
        temporaryStore.put(prefix + "draft", "v2", Duration.ofSeconds(10));
        mutableClock.advance(Duration.ofSeconds(8));

        assertThat(temporaryStore.find(prefix + "draft")).contains("v2");
    }

    @Test
    void putIfAbsentWritesOnlyWhenMissingOrExpired() {
        assertThat(temporaryStore.putIfAbsent(prefix + "slot", "1", Duration.ofSeconds(10))).isTrue();
        assertThat(temporaryStore.putIfAbsent(prefix + "slot", "2", Duration.ofSeconds(10))).isFalse();
        assertThat(temporaryStore.find(prefix + "slot")).contains("1");

        mutableClock.advance(Duration.ofSeconds(11));

        assertThat(temporaryStore.putIfAbsent(prefix + "slot", "3", Duration.ofSeconds(10)))
                .as("bản đã hết hạn coi như không có").isTrue();
        assertThat(temporaryStore.find(prefix + "slot")).contains("3");

        temporaryStore.delete(prefix + "slot");
        assertThat(temporaryStore.putIfAbsent(prefix + "slot", "4", Duration.ofSeconds(10))).isTrue();
    }

    @Test
    void takeReturnsValueOnceAndIgnoresExpired() {
        temporaryStore.put(prefix + "challenge", "phien", Duration.ofSeconds(90));

        assertThat(temporaryStore.take(prefix + "challenge")).contains("phien");
        assertThat(temporaryStore.take(prefix + "challenge")).isEmpty();

        temporaryStore.put(prefix + "challenge", "cu", Duration.ofSeconds(90));
        mutableClock.advance(Duration.ofSeconds(91));

        assertThat(temporaryStore.take(prefix + "challenge")).isEmpty();
        assertThat(countEntries(prefix + "challenge")).as("take xoá luôn bản hết hạn").isZero();
    }

    @Test
    void windowCounterKeepsFirstExpiryAndResetsAfterIt() {
        String key = prefix + "login_fail";
        assertThat(temporaryStore.incrementWithinWindow(key, Duration.ofMinutes(5))).isEqualTo(1);
        mutableClock.advance(Duration.ofMinutes(4));
        assertThat(temporaryStore.incrementWithinWindow(key, Duration.ofMinutes(5))).isEqualTo(2);
        assertThat(temporaryStore.findCounter(key)).isEqualTo(2);

        // Lần tăng thứ hai không kéo dài cửa sổ: 5 phút tính từ lần đầu.
        mutableClock.advance(Duration.ofMinutes(1));
        assertThat(temporaryStore.findCounter(key)).isZero();
        assertThat(temporaryStore.incrementWithinWindow(key, Duration.ofMinutes(5))).isEqualTo(1);

        mutableClock.advance(Duration.ofMinutes(4));
        assertThat(temporaryStore.findCounter(key)).as("cửa sổ mới bắt đầu từ lần tăng sau khi hết hạn").isEqualTo(1);

        temporaryStore.deleteCounter(key);
        assertThat(temporaryStore.findCounter(key)).isZero();
    }

    @Test
    void refreshingCounterExtendsExpiryOnEveryIncrement() {
        String key = prefix + "retake";
        assertThat(temporaryStore.incrementRefreshingTtl(key, Duration.ofHours(1))).isEqualTo(1);
        mutableClock.advance(Duration.ofMinutes(50));
        assertThat(temporaryStore.incrementRefreshingTtl(key, Duration.ofHours(1))).isEqualTo(2);
        mutableClock.advance(Duration.ofMinutes(50));

        assertThat(temporaryStore.findCounter(key)).as("TTL làm mới ở lần tăng thứ hai").isEqualTo(2);

        mutableClock.advance(Duration.ofMinutes(11));
        assertThat(temporaryStore.findCounter(key)).isZero();
        assertThat(temporaryStore.incrementRefreshingTtl(key, Duration.ofHours(1))).isEqualTo(1);
    }

    @Test
    void concurrentIncrementsFromTwoThreadsAreAtomic() throws Exception {
        String key = prefix + "otp_rate";
        int perThread = 100;
        CountDownLatch startSignal = new CountDownLatch(1);
        Callable<List<Long>> task = () -> {
            startSignal.await();
            List<Long> results = new ArrayList<>();
            for (int i = 0; i < perThread; i++) {
                results.add(temporaryStore.incrementWithinWindow(key, Duration.ofHours(1)));
            }
            return results;
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Long>> first = executor.submit(task);
            Future<List<Long>> second = executor.submit(task);
            startSignal.countDown();

            Set<Long> returned = new TreeSet<>(first.get(60, TimeUnit.SECONDS));
            returned.addAll(second.get(60, TimeUnit.SECONDS));

            // Mỗi lần tăng nhận một giá trị riêng: không có hai lần đọc cùng số rồi ghi đè nhau.
            assertThat(returned).isEqualTo(LongStream.rangeClosed(1, 2L * perThread).boxed()
                    .collect(Collectors.toCollection(TreeSet::new)));
            assertThat(temporaryStore.findCounter(key)).isEqualTo(2L * perThread);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentPutIfAbsentLetsOnlyOneWin() throws Exception {
        String key = prefix + "media";
        CountDownLatch startSignal = new CountDownLatch(1);
        Callable<Boolean> task = () -> {
            startSignal.await();
            return temporaryStore.putIfAbsent(key, "1", Duration.ofDays(30));
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(task);
            Future<Boolean> second = executor.submit(task);
            startSignal.countDown();

            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deleteExpiredRemovesOnlyExpiredRows() {
        temporaryStore.put(prefix + "old-entry", "x", Duration.ofSeconds(5));
        temporaryStore.put(prefix + "live-entry", "y", Duration.ofMinutes(5));
        temporaryStore.incrementWithinWindow(prefix + "old-counter", Duration.ofSeconds(5));
        temporaryStore.incrementWithinWindow(prefix + "live-counter", Duration.ofMinutes(5));

        mutableClock.advance(Duration.ofSeconds(6));
        int deleted = temporaryStore.deleteExpired();

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThat(countEntries(prefix + "old-entry")).isZero();
        assertThat(countCounters(prefix + "old-counter")).isZero();
        assertThat(temporaryStore.find(prefix + "live-entry")).contains("y");
        assertThat(temporaryStore.findCounter(prefix + "live-counter")).isEqualTo(1);
    }

    @Test
    void writesSurviveRollbackOfCallerReadOnlyTransaction() {
        String key = prefix + "login_fail";
        TransactionTemplate readOnlyTransaction = new TransactionTemplate(platformTransactionManager);
        readOnlyTransaction.setReadOnly(true);

        readOnlyTransaction.executeWithoutResult(status -> {
            temporaryStore.incrementWithinWindow(key, Duration.ofMinutes(5));
            temporaryStore.put(prefix + "block", "blocked", Duration.ofMinutes(15));
            status.setRollbackOnly();
        });

        assertThat(temporaryStore.findCounter(key)).isEqualTo(1);
        assertThat(temporaryStore.exists(prefix + "block")).isTrue();
    }

    private int countEntries(String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_temporary_entries WHERE entry_key = ?", Integer.class, key);
        return count == null ? 0 : count;
    }

    private int countCounters(String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_rate_counters WHERE counter_key = ?", Integer.class, key);
        return count == null ? 0 : count;
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    /** Đồng hồ chỉnh tay được, an toàn khi nhiều luồng đọc cùng lúc. */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        MutableClock(Instant start) {
            this.current = new AtomicReference<>(start);
        }

        void set(Instant instant) {
            current.set(instant);
        }

        void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
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
            return current.get();
        }
    }
}
