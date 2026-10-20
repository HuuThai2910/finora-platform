package com.finora.investment.repository.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.common.observability.OperationalMetricsProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Câu đếm gauge vận hành của Investment trên schema thật (Flyway, PostgreSQL 17). Ba loại sự cố lệnh góp vốn phải rời
 * nhau và cộng lại bằng đúng hàng đợi admin {@code findIncidents}; các câu ghi là dùng index có index phù hợp.
 */
@Testcontainers
class InvestmentOperationalMetricsRepositoryIT {

    private static final int THRESHOLD = 3;

    @Container
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_investment_ops")
            .withUsername("finora_test")
            .withPassword("finora_test");

    private static final AtomicLong SEQUENCE = new AtomicLong(1);
    private static SingleConnectionDataSource dataSource;
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource());

    @BeforeAll
    static void migrateAndDisableForeignKeysForFixtures() throws SQLException {
        Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration").load().migrate();
        // Chỉ trong DB test: bỏ qua trigger FK để tạo lệnh mà không cần dựng market listing đầy đủ; CHECK vẫn áp dụng.
        // Một kết nối duy nhất nên thiết lập session có hiệu lực cho mọi câu của test.
        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
        }
    }

    private static synchronized SingleConnectionDataSource dataSource() {
        if (dataSource == null) {
            dataSource = new SingleConnectionDataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(),
                    POSTGRESQL.getPassword(), true);
        }
        return dataSource;
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM investment_orders");
        jdbc.update("DELETE FROM investment_outbox_events");
    }

    @Test
    void orderIncidentsAreDisjointAndAddUpToTheAdminQueue() {
        order("PENDING_FUNDS", null, 3, 0, null);          // stuck: đã thử 3 lần
        order("PENDING_FUNDS", null, 1, 0, null);          // worker còn tự xử lý
        order("CANCELLED", "HOLD-1", 0, 4, null);          // nhả thử lại 4 lần
        order("REJECTED", "HOLD-2", 0, 1, null);           // nhả mới thử 1 lần
        order("CANCELLED", "HOLD-3", 0, 5, "PAYMENT_HOLD_NOT_RELEASABLE"); // nhả bị dừng
        order("COMMITTED", "HOLD-4", 0, 0, null);          // lệnh bình thường
        InvestmentOperationalMetricsRepository repository = repository(10_000);

        long stuck = repository.ordersPendingFundsStuck(THRESHOLD);
        long retrying = repository.ordersReleaseRetrying(THRESHOLD);
        long blocked = repository.ordersReleaseBlocked();

        assertThat(stuck).isEqualTo(1);
        assertThat(retrying).isEqualTo(1);
        assertThat(blocked).isEqualTo(1);
        Long adminQueue = jdbc.queryForObject("""
                SELECT count(*) FROM investment_orders o
                WHERE o.release_blocked_at IS NOT NULL
                   OR (o.status = 'PENDING_FUNDS' AND o.pending_attempt_count >= ?)
                   OR (o.status IN ('CANCELLED', 'REJECTED') AND o.payment_hold_reference IS NOT NULL
                       AND o.payment_released_at IS NULL AND o.release_attempt_count >= ?)
                """, Long.class, THRESHOLD, THRESHOLD);
        assertThat(stuck + retrying + blocked).isEqualTo(adminQueue);
    }

    @Test
    void outboxCountsAndAge() {
        outbox("PENDING", Duration.ofMinutes(20));
        outbox("PROCESSING", Duration.ofMinutes(5));
        outbox("DEAD_LETTER", Duration.ofHours(2));
        outbox("PUBLISHED", Duration.ofHours(6));
        InvestmentOperationalMetricsRepository repository = repository(10_000);

        assertThat(repository.pendingOutboxEvents()).isEqualTo(1);
        assertThat(repository.deadLetterOutboxEvents()).isEqualTo(1);
        assertThat(repository.oldestUnpublishedOutboxAgeSeconds()).isBetween(1_190.0, 1_300.0);
        assertThat(repository(1).deadLetterOutboxEvents()).isEqualTo(1);
    }

    @Test
    void indexBackedCountsHaveAMatchingIndex() {
        jdbc.execute("SET enable_seqscan = off");
        try {
            assertUsesIndex("investment_orders WHERE status = 'PENDING_FUNDS' AND release_blocked_at IS NULL"
                    + " AND pending_attempt_count >= 3", "ix_order_pending_due");
            assertUsesIndex("investment_orders WHERE status IN ('CANCELLED', 'REJECTED')"
                    + " AND payment_hold_reference IS NOT NULL AND payment_released_at IS NULL"
                    + " AND release_blocked_at IS NULL AND release_attempt_count >= 3", "ix_order_release_due");
            assertUsesIndex("investment_outbox_events WHERE status = 'PENDING'", "idx_investment_outbox_events_due");
        } finally {
            jdbc.execute("RESET enable_seqscan");
        }
    }

    private void assertUsesIndex(String fromWhere, String... acceptableIndexes) {
        List<String> plan = jdbc.queryForList(
                "EXPLAIN SELECT count(*) FROM (SELECT 1 FROM " + fromWhere + " LIMIT 10) AS bounded", String.class);
        String text = String.join("\n", plan);
        assertThat(Arrays.stream(acceptableIndexes).anyMatch(text::contains))
                .as("plan của %s phải dùng một trong %s:%n%s", fromWhere, Arrays.toString(acceptableIndexes), text)
                .isTrue();
    }

    private InvestmentOperationalMetricsRepository repository(int cap) {
        return new InvestmentOperationalMetricsRepository(dataSource(),
                new OperationalMetricsProperties(true, Duration.ofSeconds(60), Duration.ZERO, cap,
                        Duration.ofSeconds(5)));
    }

    private void order(String status, String holdReference, int pendingAttempts, int releaseAttempts,
            String releaseErrorCode) {
        long id = SEQUENCE.getAndIncrement();
        jdbc.update("""
                INSERT INTO investment_orders (order_reference, listing_id, investor_id, amount, status,
                    idempotency_key, request_hash, payment_hold_reference, pending_attempt_count,
                    release_attempt_count, release_error_code, release_blocked_at, created_by, updated_by,
                    created_at, updated_at)
                VALUES (?, 1, 'investor-1', 1000000, ?, ?, repeat('a', 64), ?, ?, ?, ?::varchar,
                    CASE WHEN ?::varchar IS NOT NULL THEN now() END, 'test', 'test', now(), now())
                """, "ORD-OPS-" + id, status, "key-" + id, holdReference, pendingAttempts, releaseAttempts,
                releaseErrorCode, releaseErrorCode);
    }

    private void outbox(String status, Duration age) {
        jdbc.update("""
                INSERT INTO investment_outbox_events (event_id, aggregate_type, aggregate_id, event_type,
                    event_version, payload_json, status, attempt_count, available_at, processing_started_at,
                    processing_token, published_at, trace_id, created_at, updated_at)
                VALUES (gen_random_uuid(), 'MarketListing', 'L-1', 'LoanFullyFunded', 2, '{}'::jsonb, ?::varchar, 1,
                    now(), CASE WHEN ?::varchar = 'PROCESSING' THEN now() END,
                    CASE WHEN ?::varchar = 'PROCESSING' THEN gen_random_uuid() END,
                    CASE WHEN ?::varchar = 'PUBLISHED' THEN now() END, 'trace-ops',
                    now() - (?::double precision * interval '1 second'), now())
                """, status, status, status, status, age.toSeconds());
    }
}
