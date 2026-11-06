package com.finora.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Access log ghi mỗi lượt gọi đi qua Gateway thành một dòng JSON (logger {@code finora.api}), kể cả khi service phía
 * sau không chạy. Không service nào chạy trong test nên mọi lượt proxy đều thành lỗi kết nối.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiLogFilterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void logsProxiedCallWithMaskedBodyAndUpstreamError(CapturedOutput output) throws Exception {
        try {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("User-Agent", "Mozilla/5.0")
                    .content("{\"email\":\"admin@finora.vn\",\"password\":\"Admin@123\"}"));
        } catch (Exception upstreamDown) {
            // Gateway ném lỗi kết nối khi User Service không chạy; access log vẫn phải có lượt gọi.
        }

        JsonNode line = lastLine(output, "/api/v1/auth/login");
        assertThat(line.path("method").asText()).isEqualTo("POST");
        assertThat(line.path("service").asText()).isEqualTo("user-auth-service");
        // 5xx khi máy test không chạy User Service; nếu có service đang chạy ở cổng 8085 thì là lỗi đăng nhập của nó.
        assertThat(line.path("status").asInt()).isGreaterThanOrEqualTo(400);
        assertThat(line.path("client").asText()).isEqualTo("WEB");
        assertThat(line.path("requestBody").asText()).contains("admin@finora.vn").contains("\"password\":\"***\"");
        assertThat(line.path("errorMessage").asText()).isNotBlank();
        assertThat(output.getOut()).doesNotContain("Admin@123");
    }

    @Test
    void logsBlockedInternalRouteAndCaller(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/v1/internal/users/1/identity")
                        .header("Authorization", "Bearer " + unsignedToken("hai", "INVESTOR"))
                        .header("User-Agent", "okhttp/4.12.0"))
                .andExpect(status().isNotFound());

        JsonNode line = lastLine(output, "/api/v1/internal/users/1/identity");
        assertThat(line.path("status").asInt()).isEqualTo(404);
        assertThat(line.path("service").asText()).isEqualTo("gateway");
        assertThat(line.path("user").asText()).isEqualTo("hai");
        assertThat(line.path("role").asText()).isEqualTo("INVESTOR");
        assertThat(line.path("client").asText()).isEqualTo("MOBILE");
        assertThat(line.path("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(line.path("errorMessage").asText()).isEqualTo("Không tìm thấy tài nguyên");
    }

    @Test
    void skipsPreflight(CapturedOutput output) throws Exception {
        mockMvc.perform(options("/api/v1/internal/preflight-check"));

        assertThat(output.getOut()).doesNotContain("preflight-check");
    }

    @Test
    void readsSpringSecurityChallenge() {
        assertThat(ApiLogFilter.challengeMessage("Bearer")).isEqualTo("Chưa đăng nhập (request không có access token)");
        assertThat(ApiLogFilter.challengeMessage(
                "Bearer error=\"invalid_token\", error_description=\"Jwt expired at 2026-11-03T10:00:00Z\""))
                .isEqualTo("Jwt expired at 2026-11-03T10:00:00Z");
        assertThat(ApiLogFilter.challengeMessage(null)).isNull();
    }

    /** Dòng {@code finora.api} mới nhất của đường dẫn này, phần JSON sau dấu {@code :} của mẫu log Spring. */
    private static JsonNode lastLine(CapturedOutput output, String path) throws Exception {
        List<String> lines = output.getOut().lines()
                .filter(line -> line.contains("finora.api") && line.contains("\"path\":\"" + path + "\""))
                .toList();
        assertThat(lines).as("dòng access log của " + path).isNotEmpty();
        String last = lines.get(lines.size() - 1);
        return JSON.readTree(last.substring(last.indexOf('{')));
    }

    private static String unsignedToken(String username, String role) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String payload = "{\"preferred_username\":\"" + username + "\",\"realm_access\":{\"roles\":[\"" + role + "\"]}}";
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }
}
