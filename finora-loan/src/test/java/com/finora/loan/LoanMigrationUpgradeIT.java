package com.finora.loan;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LoanMigrationUpgradeIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_loan_upgrade_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Test
    void existingV4DatabaseUpgradesThroughV30WithoutRecreatingOldTables() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations("classpath:db/migration")
                .target("4")
                .load()
                .migrate();

        Flyway upgraded = Flyway.configure()
                .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        upgraded.migrate();

        assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("30");
        assertThat(upgraded.info().pending()).isEmpty();
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name = 'loan_applications'
                       AND column_name IN ('funding_cancelled_at', 'funding_cancel_reason')
                     """)) {
            // V27 bồi hoàn gọi vốn phải nâng cấp được trên DB cũ mà không đổi dữ liệu FULLY_FUNDED hiện có.
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(2);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_name IN (
                           'loan_contracts',
                           'loan_contract_status_histories',
                           'loan_contract_documents',
                           'loan_contract_parties',
                           'loan_processed_events',
                           'loan_disbursement_sagas',
                           'finora_loans',
                           'loan_servicing_projections',
                           'loan_reschedule_requests',
                           'loan_collection_cases',
                           'loan_collection_actions',
                           'loan_reconciliation_incidents',
                           'loan_repayment_event_quarantine'
                       )
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(13);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name = 'loan_contract_parties'
                       AND column_name IN (
                           'signature_document_id',
                           'signature_requested_at',
                           'payment_hold_reference'
                       )
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(3);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM pg_indexes
                     WHERE schemaname = 'public'
                       AND indexname IN (
                           'idx_loan_products_admin_created',
                           'idx_loan_products_core_sync_created',
                           'idx_loan_applications_admin_created',
                           'idx_loan_applications_admin_status_created'
                       )
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(4);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND (
                           (table_name = 'loan_products'
                            AND column_name IN ('min_annual_interest_rate', 'max_annual_interest_rate'))
                           OR
                           (table_name = 'loan_applications'
                            AND column_name IN (
                                'final_annual_interest_rate',
                                'pricing_credit_grade',
                                'pricing_policy_version',
                                'final_calculation_snapshot_id',
                                'decision_source'
                            ))
                       )
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(7);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM pg_indexes
                     WHERE schemaname = 'public'
                       AND indexname = 'uq_schedule_snapshot_application_purpose'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name = 'loan_applications'
                       AND column_name IN (
                           'terms_confirmation_status',
                           'terms_version',
                           'terms_hash',
                           'terms_expires_at',
                           'terms_responded_by',
                           'terms_responded_at',
                           'terms_decline_reason_code',
                           'terms_decline_reason_detail',
                           'terms_consent_idempotency_key',
                           'terms_consent_request_hash'
                       )
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(10);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM pg_indexes
                     WHERE schemaname = 'public'
                       AND indexname IN (
                           'uq_loan_application_terms_consent_key',
                           'idx_loan_application_pending_terms_expiry'
                       )
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(2);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name = 'loan_contracts'
                       AND column_name IN (
                           'signature_provider',
                           'signature_transaction_id',
                           'signature_evidence_hash',
                           'signature_document_id',
                           'signature_requested_at'
                       )
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(5);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_name = 'loan_outbox_events'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM pg_indexes
                     WHERE schemaname = 'public'
                       AND indexname = 'idx_loan_servicing_projection_stale_queue'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM pg_indexes
                     WHERE schemaname = 'public'
                       AND indexname IN (
                           'uq_loan_reconciliation_one_open_type',
                           'idx_loan_reconciliation_admin_queue',
                           'idx_loan_reconciliation_history'
                       )
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(3);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT indexname, indexdef
                     FROM pg_indexes
                     WHERE schemaname = 'public'
                       AND tablename = 'loan_disbursement_sagas'
                       AND indexname IN ('idx_loan_disbursement_saga_due', 'idx_loan_disbursement_saga_worker_due')
                     """)) {
            // V28 thay index worker cũ: saga CORE_BOOKING hết lease cũng phải được quét bằng index.
            assertThat(result.next()).isTrue();
            assertThat(result.getString("indexname")).isEqualTo("idx_loan_disbursement_saga_worker_due");
            assertThat(result.getString("indexdef")).contains("CORE_BOOKING_PENDING", "RETRY_PENDING", "'CORE_BOOKING'");
            assertThat(result.next()).isFalse();
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name = 'loan_applications'
                       AND column_name LIKE 'kyc_snapshot_%'
                     """)) {
            // V29 thêm snapshot eKYC lúc nộp; hồ sơ cũ để trống toàn bộ cột.
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(7);
        }
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name = 'loan_contracts'
                       AND column_name = 'early_settlement_policy_version'
                     """)) {
            // V30 ghi biểu phí trả trước mà hợp đồng đã công bố (G-POLICY-W2).
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }
}
