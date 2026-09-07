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
    void existingV4DatabaseUpgradesThroughV9WithoutRecreatingOldTables() throws Exception {
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

        assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("9");
        assertThat(upgraded.info().pending()).isEmpty();
        try (Connection connection = POSTGRESQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_name IN (
                           'loan_contracts',
                           'loan_contract_status_histories',
                           'loan_contract_documents'
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
    }
}
