package com.finora.loan.repository.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.common.observability.OperationalMetricsProperties;
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
 * Câu đếm gauge vận hành chạy đúng trên schema thật của Loan (Flyway từ V1 tới hiện hành, PostgreSQL 17), có trần, và
 * các câu được ghi là dùng index thật sự có index phù hợp (EXPLAIN với {@code enable_seqscan = off}: nếu không có index
 * dùng được, planner vẫn phải chọn Seq Scan).
 */
@Testcontainers
class LoanOperationalMetricsRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_loan_ops")
            .withUsername("finora_test")
            .withPassword("finora_test");

    private static final AtomicLong SAGA_SEQUENCE = new AtomicLong(1);
    // Một kết nối duy nhất: SET enable_seqscan trong test phải áp cho đúng kết nối chạy EXPLAIN.
    private static SingleConnectionDataSource dataSource;
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource());

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration").load().migrate();
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
        jdbc.update("DELETE FROM loan_outbox_events");
        jdbc.update("DELETE FROM loan_disbursement_sagas");
        jdbc.update("DELETE FROM loan_repayment_event_quarantine");
    }

    @Test
    void countsEachQueueByStatusOnly() {
        outbox("PENDING", Duration.ofMinutes(10));
        outbox("PENDING", Duration.ofMinutes(2));
        outbox("PROCESSING", Duration.ofMinutes(30));
        outbox("PUBLISHED", Duration.ofHours(5));
        outbox("DEAD_LETTER", Duration.ofHours(1));
        saga("REPAIR_REQUIRED");
        saga("COMPLETED");
        quarantine("PENDING");
        quarantine("RESOLVED");
        LoanOperationalMetricsRepository repository = repository(10_000);

        assertThat(repository.pendingOutboxEvents()).isEqualTo(2);
        assertThat(repository.deadLetterOutboxEvents()).isEqualTo(1);
        assertThat(repository.disbursementSagasRepairRequired()).isEqualTo(1);
        assertThat(repository.pendingRepaymentEventQuarantine()).isEqualTo(1);
        assertThat(repository.openServicingReconciliationIncidents()).isZero();
        // Event PROCESSING tạo 30 phút trước là event chưa gửi cũ nhất; PUBLISHED/DEAD_LETTER không tính vào độ trễ.
        assertThat(repository.oldestUnpublishedOutboxAgeSeconds()).isBetween(1_790.0, 1_900.0);
    }

    @Test
    void countStopsAtTheCapAndAgeIsZeroWhenNothingIsWaiting() {
        for (int i = 0; i < 5; i++) {
            outbox("DEAD_LETTER", Duration.ofMinutes(i));
        }
        LoanOperationalMetricsRepository repository = repository(3);

        assertThat(repository.deadLetterOutboxEvents()).isEqualTo(3);
        assertThat(repository.oldestUnpublishedOutboxAgeSeconds()).isZero();
    }

    @Test
    void indexBackedCountsHaveAMatchingIndex() {
        jdbc.execute("SET enable_seqscan = off");
        try {
            assertUsesIndex("loan_outbox_events WHERE status = 'PENDING'", "idx_loan_outbox_events_due");
            assertUsesIndex("loan_disbursement_sagas WHERE status = 'REPAIR_REQUIRED'",
                    "idx_loan_disbursement_sagas_status_updated");
            // Hai index đều đúng: hàng đợi admin (status đứng đầu) hoặc unique một phần WHERE status = 'OPEN'.
            assertUsesIndex("loan_reconciliation_incidents WHERE status = 'OPEN'",
                    "idx_loan_reconciliation_admin_queue", "uq_loan_reconciliation_one_open_type");
            assertUsesIndex("loan_repayment_event_quarantine WHERE status = 'PENDING'",
                    "idx_loan_repayment_event_quarantine_work", "idx_loan_repayment_event_quarantine_application");
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

    private LoanOperationalMetricsRepository repository(int cap) {
        return new LoanOperationalMetricsRepository(dataSource(),
                new OperationalMetricsProperties(true, Duration.ofSeconds(60), Duration.ZERO, cap,
                        Duration.ofSeconds(5)));
    }

    private void outbox(String status, Duration age) {
        jdbc.update("""
                INSERT INTO loan_outbox_events (event_id, aggregate_type, aggregate_id, event_type, event_version,
                    payload_json, status, attempt_count, available_at, processing_started_at, processing_token,
                    published_at, trace_id, created_at, updated_at, publishable)
                VALUES (gen_random_uuid(), 'LoanApplication', 'LA-1', 'LoanFundingRequested', 1, '{}'::jsonb,
                    ?::varchar, 1, now(),
                    CASE WHEN ?::varchar <> 'PENDING' THEN now() END,
                    CASE WHEN ?::varchar = 'PROCESSING' THEN gen_random_uuid() END,
                    CASE WHEN ?::varchar = 'PUBLISHED' THEN now() END,
                    'trace-ops', now() - (?::double precision * interval '1 second'), now(), true)
                """, status, status, status, status, age.toSeconds());
    }

    private void saga(String status) {
        long id = SAGA_SEQUENCE.getAndIncrement();
        jdbc.update("""
                INSERT INTO loan_disbursement_sagas (saga_id, loan_application_id, contract_number, listing_id,
                    borrower_id, amount, currency, status, attempt_count, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, 1, 'borrower-1', 1000000, 'VND', ?, 1, now(), now())
                """, id, "LC-OPS-" + id, status);
    }

    private void quarantine(String status) {
        jdbc.update("""
                INSERT INTO loan_repayment_event_quarantine (event_id, loan_application_id, repayment_id, payload_json,
                    received_at, status, reason_code, created_at, updated_at)
                VALUES (gen_random_uuid(), 1, gen_random_uuid(), '{}', now(), ?, 'MAPPING_MISSING', now(), now())
                """, status);
    }
}
