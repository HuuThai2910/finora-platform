package com.finora.blockchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.blockchain.config.BlockchainOperationsConfig;
import com.finora.common.observability.OperationalMonitoringScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Health của Blockchain mở cho probe nhưng API còn lại vẫn cần đăng nhập như trước (W3E, D56); gauge hàng đợi proof đếm
 * đúng trạng thái (D58).
 */
@SpringBootTest(properties = "finora.operations.metrics.initial-delay=1h")
@AutoConfigureMockMvc
@Testcontainers
class BlockchainOperationsHealthIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_blockchain_health")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired MockMvc mockMvc;
    @Autowired MeterRegistry meterRegistry;
    @Autowired OperationalMonitoringScheduler scheduler;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void probesAreOpenAndTheRestOfTheApiStaysProtected() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        // Listener Kafka tắt mặc định nên không có health Kafka.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.kafka").doesNotExist());
        // API proof vẫn cần đăng nhập: 401 (HTTP Basic của chain mặc định, hoặc JWT khi đã có BlockchainSecurityConfig
        // của W3D) hoặc chuyển tới form đăng nhập của chain mặc định.
        mockMvc.perform(get("/api/v1/blockchain/proofs"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(302, 401));
    }

    @Test
    void proofQueueGaugesCountByStatus() {
        jdbcTemplate.update("DELETE FROM blockchain_proof_submissions");
        insertProof("PENDING");
        insertProof("DEAD_LETTER");
        insertProof("DEAD_LETTER");

        scheduler.refreshNow();

        assertThat(meterRegistry.get(BlockchainOperationsConfig.PROOF_SUBMISSIONS).tag("status", "pending")
                .gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get(BlockchainOperationsConfig.PROOF_SUBMISSIONS).tag("status", "dead_letter")
                .gauge().value()).isEqualTo(2.0);
        assertThat(meterRegistry.get(BlockchainOperationsConfig.PROOF_OLDEST_PENDING_AGE).gauge().value())
                .isGreaterThanOrEqualTo(0.0);
    }

    private void insertProof(String status) {
        jdbcTemplate.update("""
                INSERT INTO blockchain_proof_submissions (proof_id, source_service, source_event_id, aggregate_type,
                    aggregate_id, proof_type, payload_hash, payload_version, provider, status, attempt_count,
                    available_at, created_at, updated_at)
                VALUES (?, 'finora-payment', ?, 'Repayment', 'R-1', 'REPAYMENT', repeat('a', 64), 1, 'MOCK', ?, 1,
                    now(), now(), now())
                """, UUID.randomUUID(), UUID.randomUUID(), status);
    }
}
