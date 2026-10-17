package com.finora.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.common.observability.OperationalGauge;
import com.finora.common.observability.OperationalMonitoringScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * B23: Payment khởi động và sẵn sàng phục vụ mà không có Redis (đã gỡ dependency), cùng nhóm health và gauge vận hành
 * (W3E, D56–D58). Không cần Kafka hay Fineract để readiness UP.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "finora.payment.scheduling-enabled=false",
        // Cổng 1 từ chối kết nối ngay: kết quả không phụ thuộc máy có broker local hay không.
        "spring.kafka.bootstrap-servers=localhost:1",
        "finora.operations.health.kafka.timeout=1s",
        "finora.operations.metrics.initial-delay=1h"
})
@AutoConfigureMockMvc
@Testcontainers
class PaymentOperationsHealthIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_payment_health")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired ApplicationContext context;
    @Autowired MockMvc mockMvc;
    @Autowired MeterRegistry meterRegistry;
    @Autowired OperationalMonitoringScheduler scheduler;
    // Thay JWKS của Keycloak: token đi qua đúng bearer resolver và KeycloakJwtAuthenticationConverter của service.
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void paymentStartsWithoutAnyRedisBeanAndIsReady() throws Exception {
        assertThat(context.getBeanNamesForType(
                org.springframework.boot.actuate.health.HealthContributor.class)).doesNotContain("redisHealthContributor");
        assertThat(isOnClasspath("org.springframework.data.redis.connection.RedisConnectionFactory")).isFalse();

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.redis").doesNotExist())
                .andExpect(jsonPath("$.components.kafka").doesNotExist());
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/operations"))
                .andExpect(jsonPath("$.components.kafka.status").value("DOWN"));
    }

    @Test
    void reconciliationGaugesAreRegisteredAndMetricsNeedAdmin() throws Exception {
        scheduler.refreshNow();

        for (String type : new String[] {"repayment_reconciliation_required", "disbursement_reconciliation_required",
                "topup_reconciliation_required"}) {
            assertThat(meterRegistry.get(OperationalGauge.INCIDENTS).tag("service", "payment").tag("type", type)
                    .gauge().value()).isZero();
        }
        assertThat(meterRegistry.get(OperationalGauge.OUTBOX_OLDEST_PENDING_AGE).tag("service", "payment")
                .gauge().value()).isZero();

        when(jwtDecoder.decode("investor-token")).thenReturn(token("investor-token", "INVESTOR"));
        when(jwtDecoder.decode("admin-token")).thenReturn(token("admin-token", "ADMIN"));

        mockMvc.perform(get("/actuator/metrics/" + OperationalGauge.INCIDENTS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics/" + OperationalGauge.INCIDENTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer investor-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics/" + OperationalGauge.INCIDENTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    private static Jwt token(String value, String realmRole) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(value)
                .header("alg", "RS256")
                .subject("user-" + realmRole.toLowerCase())
                .claim("realm_access", Map.of("roles", List.of(realmRole)))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }

    private static boolean isOnClasspath(String className) {
        try {
            Class.forName(className, false, PaymentOperationsHealthIT.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}
