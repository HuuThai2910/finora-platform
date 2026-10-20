package com.finora.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Notification khởi động với cấu hình health của W3E (D56): readiness chỉ cần PostgreSQL, SMTP không bị kiểm mỗi lần
 * health, Kafka nằm ở nhóm {@code operations} không chặn traffic.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // Cổng 1 từ chối kết nối ngay: kết quả không phụ thuộc máy có broker local hay không.
        "spring.kafka.bootstrap-servers=localhost:1",
        "finora.operations.health.kafka.timeout=1s"
})
@AutoConfigureMockMvc
@Testcontainers
class NotificationHealthProbesIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_notification_health")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired MockMvc mockMvc;

    @Test
    void readinessNeedsOnlyTheDatabaseAndMailIsNotProbed() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.components.mail").doesNotExist())
                .andExpect(jsonPath("$.components.kafka.status").value("DOWN"));
        mockMvc.perform(get("/actuator/health/operations"))
                .andExpect(jsonPath("$.components.kafka.status").value("DOWN"));
    }
}
