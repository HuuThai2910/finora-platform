package com.finora.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.common.observability.OperationalGauge;
import com.finora.common.observability.OperationalMonitoringScheduler;
import com.finora.loan.config.LoanOperationsConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Nhóm health và gauge vận hành của Loan (W3E, D56–D58) trên context thật: liveness/readiness không phụ thuộc Kafka hay
 * Fineract, nhóm {@code operations} báo Kafka và job cuối ngày, metric chỉ cho ADMIN.
 */
@SpringBootTest(properties = {
        "finora.ai.credit.worker-enabled=false",
        "finora.loan.contract.expiry-worker-enabled=false",
        "finora.loan.servicing-sync.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "finora.loan.outbox.publisher-delay=3600000",
        // Cổng 1 từ chối kết nối ngay: kết quả Kafka health không phụ thuộc máy có broker local hay không.
        "spring.kafka.bootstrap-servers=localhost:1",
        "finora.operations.health.kafka.timeout=1s",
        // Test tự gọi refreshNow(); luồng định kỳ không chạy trong thời gian test.
        "finora.operations.metrics.initial-delay=1h"
})
@AutoConfigureMockMvc
@Testcontainers
class LoanOperationsHealthIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_loan_health")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired MockMvc mockMvc;
    @Autowired MeterRegistry meterRegistry;
    @Autowired OperationalMonitoringScheduler scheduler;

    @Test
    void livenessAndReadinessAreUpWithoutKafkaOrFineract() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.kafka").doesNotExist())
                .andExpect(jsonPath("$.components.fineractEndOfDay").doesNotExist());
    }

    @Test
    void operationsGroupReportsKafkaAndEndOfDayWithDetailsOnlyForAdmin() throws Exception {
        mockMvc.perform(get("/actuator/health/operations"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.kafka.status").value("DOWN"))
                // Booking provider mặc định là mock: kiểm tra EOD không áp dụng, không kéo nhóm xuống.
                .andExpect(jsonPath("$.components.fineractEndOfDay.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.components.fineractEndOfDay.details").doesNotExist());

        mockMvc.perform(get("/actuator/health/operations")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(jsonPath("$.components.fineractEndOfDay.details.reason")
                        .value("FINERACT_EOD_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.components.kafka.details.error").exists());
    }

    @Test
    void operationalGaugesAreRegisteredAndMetricsNeedAdmin() throws Exception {
        scheduler.refreshNow();

        assertThat(meterRegistry.get(OperationalGauge.INCIDENTS).tag("service", "loan")
                .tag("type", "disbursement_saga_repair_required").gauge().value()).isZero();
        assertThat(meterRegistry.get(OperationalGauge.OUTBOX_EVENTS).tag("status", "dead_letter").gauge().value())
                .isZero();
        assertThat(meterRegistry.get(LoanOperationsConfig.FINERACT_EOD_LAST_SUCCESS_AGE).tag("job", "LA_ARAG")
                .gauge().value()).isNaN();

        mockMvc.perform(get("/actuator/metrics/" + OperationalGauge.INCIDENTS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics/" + OperationalGauge.INCIDENTS)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BORROWER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics/" + OperationalGauge.INCIDENTS)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(OperationalGauge.INCIDENTS));
    }
}
