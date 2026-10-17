package com.finora.payment.repository.operations;

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
 * Câu đếm gauge vận hành của Payment trên schema thật (Flyway, PostgreSQL 17): đếm đúng trạng thái, có trần, và các câu
 * được ghi là dùng index có index phù hợp (EXPLAIN với {@code enable_seqscan = off}).
 */
@Testcontainers
class PaymentOperationalMetricsRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_payment_ops")
            .withUsername("finora_test")
            .withPassword("finora_test");

    private static final AtomicLong SEQUENCE = new AtomicLong(1);
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
        jdbc.update("DELETE FROM payment_outbox_events");
        jdbc.update("DELETE FROM payment_disbursements");
        jdbc.update("DELETE FROM payment_repayments");
    }

    @Test
    void countsEachQueueByStatusOnly() {
        outbox("PENDING", Duration.ofMinutes(15));
        outbox("PUBLISHED", Duration.ofHours(3));
        outbox("DEAD_LETTER", Duration.ofHours(1));
        outbox("DEAD_LETTER", Duration.ofMinutes(5));
        disbursement("RECONCILIATION_REQUIRED");
        disbursement("COMPLETED");
        repayment("RECONCILIATION_REQUIRED");
        repayment("COMPLETED");
        PaymentOperationalMetricsRepository repository = repository(10_000);

        assertThat(repository.pendingOutboxEvents()).isEqualTo(1);
        assertThat(repository.deadLetterOutboxEvents()).isEqualTo(2);
        assertThat(repository.oldestUnpublishedOutboxAgeSeconds()).isBetween(890.0, 1_000.0);
        assertThat(repository.disbursementsReconciliationRequired()).isEqualTo(1);
        assertThat(repository.repaymentsReconciliationRequired()).isEqualTo(1);
        assertThat(repository.topUpsReconciliationRequired()).isZero();
    }

    @Test
    void countStopsAtTheCap() {
        for (int i = 0; i < 4; i++) {
            disbursement("RECONCILIATION_REQUIRED");
        }

        assertThat(repository(2).disbursementsReconciliationRequired()).isEqualTo(2);
        assertThat(repository(2).oldestUnpublishedOutboxAgeSeconds()).isZero();
    }

    @Test
    void indexBackedCountsHaveAMatchingIndex() {
        jdbc.execute("SET enable_seqscan = off");
        try {
            assertUsesIndex("payment_outbox_events WHERE status = 'PENDING'", "idx_payment_outbox_due");
            assertUsesIndex("payment_repayments WHERE status = 'RECONCILIATION_REQUIRED'",
                    "idx_payment_repayments_loan_in_flight");
            assertUsesIndex("payment_disbursements WHERE status = 'RECONCILIATION_REQUIRED'",
                    "idx_payment_disbursements_incident_queue", "idx_payment_disbursement_worker_due");
            assertUsesIndex("payment_top_ups WHERE status = 'RECONCILIATION_REQUIRED'", "idx_payment_top_ups_pending");
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

    private PaymentOperationalMetricsRepository repository(int cap) {
        return new PaymentOperationalMetricsRepository(dataSource(),
                new OperationalMetricsProperties(true, Duration.ofSeconds(60), Duration.ZERO, cap,
                        Duration.ofSeconds(5)));
    }

    private void outbox(String status, Duration age) {
        jdbc.update("""
                INSERT INTO payment_outbox_events (event_id, aggregate_id, event_type, event_version, payload_json,
                    status, attempt_count, available_at, published_at, created_at, updated_at)
                VALUES (gen_random_uuid(), 'saga-1', 'DisbursementCompleted', 1, '{}'::jsonb, ?::varchar, 1, now(),
                    CASE WHEN ?::varchar = 'PUBLISHED' THEN now() END,
                    now() - (?::double precision * interval '1 second'), now())
                """, status, status, age.toSeconds());
    }

    private void disbursement(String status) {
        long id = SEQUENCE.getAndIncrement();
        jdbc.update("""
                INSERT INTO payment_disbursements (saga_id, loan_application_id, contract_number, listing_id,
                    borrower_id, amount, currency, status, provider, attempt_count, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, 1, 'borrower-1', 1000000, 'VND', ?, 'mock', 1, now(), now())
                """, id, "LC-OPS-" + id, status);
    }

    private void repayment(String status) {
        long id = SEQUENCE.getAndIncrement();
        jdbc.update("""
                INSERT INTO payment_repayments (repayment_id, idempotency_key, loan_application_id, borrower_id,
                    fineract_loan_id, amount, core_amount, currency, transaction_date, status,
                    wallet_ledger_transaction_id, attempt_count, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, 'borrower-1', ?, 1726000, 1726000, 'VND', current_date, ?,
                    gen_random_uuid(), 1, now(), now())
                """, "ops-" + id, id, id, status);
    }
}
