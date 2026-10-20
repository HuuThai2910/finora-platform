package com.finora.investment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.common.observability.OperationalGauge;
import com.finora.common.observability.OperationalMonitoringScheduler;
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

/** Nhóm health và gauge sự cố lệnh góp vốn của Investment (W3E, D56, D58) trên context thật. */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "finora.investment.payment.mode=stub",
        "finora.investment.auto-invest.enabled=false",
        "finora.investment.order-book.settlement.enabled=false",
        "finora.investment.maintenance.enabled=false",
        "finora.investment.outbox.publisher-delay=3600000",
        // Cổng 1 từ chối kết nối ngay: kết quả không phụ thuộc máy có broker local hay không.
        "spring.kafka.bootstrap-servers=localhost:1",
        "finora.operations.health.kafka.timeout=1s",
        "finora.operations.metrics.initial-delay=1h"
})
@AutoConfigureMockMvc
@Testcontainers
class InvestmentOperationsHealthIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_investment_health")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired MockMvc mockMvc;
    @Autowired MeterRegistry meterRegistry;
    @Autowired OperationalMonitoringScheduler scheduler;

    @Test
    void readinessNeedsOnlyTheDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.kafka").doesNotExist());
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/operations"))
                .andExpect(jsonPath("$.components.kafka.status").value("DOWN"));
    }

    @Test
    void orderIncidentGaugesAreRegisteredAndMetricsNeedAdmin() throws Exception {
        scheduler.refreshNow();

        for (String type : new String[] {"order_pending_funds_stuck", "order_release_retrying",
                "order_release_blocked"}) {
            assertThat(meterRegistry.get(OperationalGauge.INCIDENTS).tag("service", "investment").tag("type", type)
                    .gauge().value()).isZero();
        }

        mockMvc.perform(get("/actuator/metrics/" + OperationalGauge.INCIDENTS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics/" + OperationalGauge.INCIDENTS)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INVESTOR"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics/" + OperationalGauge.INCIDENTS)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }
}
