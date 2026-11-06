package com.finora.user.integration.cic.client;

import com.finora.user.integration.cic.client.CicMappingClient.CicMappingException;
import com.finora.user.integration.cic.contract.RegisterBorrowerMappingRequest;
import com.finora.user.util.CircuitBreakerTestSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.InterruptedIOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Đăng ký ánh xạ CIC qua RestClient: chỉ khoá nội bộ, không token service account; lỗi mang mã HTTP hoặc -1 khi không
 * có phản hồi (worker ghi {@code CIC_HTTP_-1} và hẹn chạy lại như thời Feign).
 */
class CicMappingClientTest {

    private static final String URL = "http://cic.test/api/v1/internal/borrower-mappings";

    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    private MockRestServiceServer server;
    private CicMappingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://cic.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CicMappingClient(builder.build(),
                CircuitBreakerTestSupport.circuitBreakerFactory(circuitBreakerRegistry));
    }

    @Test
    void sendsInternalKeyAndCccdWithoutServiceAccountToken() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Finora-Internal-Key", "test-internal-key"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(content().json("{\"borrowerId\":\"7\",\"soCccd\":\"036094001234\"}", true))
                .andRespond(withStatus(HttpStatus.CREATED));

        client.register("test-internal-key", new RegisterBorrowerMappingRequest("7", "036094001234"));

        server.verify();
    }

    @Test
    void rejectedMappingKeepsHttpStatus() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> client.register("k", new RegisterBorrowerMappingRequest("7", "036094001234")))
                .isInstanceOfSatisfying(CicMappingException.class, e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void openCircuitHasNoResponseStatusSoWorkerRetries() {
        circuitBreakerRegistry.circuitBreaker(CicMappingClient.CIRCUIT_BREAKER).transitionToForcedOpenState();

        assertThatThrownBy(() -> client.register("k", new RegisterBorrowerMappingRequest("7", "036094001234")))
                .isInstanceOfSatisfying(CicMappingException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(CicMappingClient.NO_RESPONSE_STATUS));
        server.verify();
    }

    @Test
    void timeLimiterCutHasNoResponseStatus() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://cic.test");
        MockRestServiceServer slowServer = MockRestServiceServer.bindTo(builder).build();
        CicMappingClient slowClient = new CicMappingClient(builder.build(),
                CircuitBreakerTestSupport.circuitBreakerFactory(CircuitBreakerRegistry.ofDefaults(), Duration.ofMillis(200)));
        slowServer.expect(requestTo(URL)).andRespond(request -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                // Time limiter huỷ lời gọi (cancel-running-future) nên luồng chạy lời gọi bị ngắt.
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("bị huỷ");
            }
            return withSuccess().createResponse(request);
        });

        assertThatThrownBy(() -> slowClient.register("k", new RegisterBorrowerMappingRequest("7", "036094001234")))
                .isInstanceOfSatisfying(CicMappingException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(CicMappingClient.NO_RESPONSE_STATUS));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
