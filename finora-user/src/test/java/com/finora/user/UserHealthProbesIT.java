package com.finora.user;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Liveness/readiness của User (W3E, D56) gọi được khi chưa có token và readiness kiểm đúng dependency riêng của
 * User: PostgreSQL (kể cả kho tạm OTP, giới hạn dò mật khẩu — User không còn dùng Redis). Keycloak không nằm
 * trong readiness.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserHealthProbesIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_user_health")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired MockMvc mockMvc;

    @Test
    void readinessChecksPostgresWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                // Chi tiết (tên DB, câu kiểm tra) chỉ dành cho admin.
                .andExpect(jsonPath("$.components.db.details").doesNotExist())
                .andExpect(jsonPath("$.components.redis").doesNotExist());
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
