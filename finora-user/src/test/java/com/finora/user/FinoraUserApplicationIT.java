package com.finora.user;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** Xác nhận User chỉ khởi động với PostgreSQL 17 và không dựa vào Hibernate tự tạo schema. */
@SpringBootTest
@Testcontainers
class FinoraUserApplicationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_user_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void contextUsesPostgreSql17AndHasNoPendingMigration() {
        assertThat(jdbcTemplate.queryForObject("SHOW server_version", String.class)).startsWith("17.");
        assertThat(flyway.info().pending()).isEmpty();
    }
}
