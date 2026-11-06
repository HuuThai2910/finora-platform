package com.finora.user.integration.ai.client;

import com.finora.user.integration.ai.client.AiEkycClient.AiEkycException;
import com.finora.user.integration.ai.client.AiEkycClient.DocumentRequest;
import com.finora.user.integration.ai.client.AiEkycClient.DocumentResult;
import com.finora.user.util.CircuitBreakerTestSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Lời gọi {@code POST /api/v1/ai/ekyc/document} qua RestClient và circuit breaker: JSON snake_case, mã HTTP của lỗi
 * giữ nguyên để nơi gọi tách ảnh hỏng (400/422) với AI không phục vụ được (-1, 5xx).
 */
class AiEkycClientTest {

    private static final String URL = "http://ai.test/api/v1/ai/ekyc/document";

    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    private MockRestServiceServer server;
    private AiEkycClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiEkycClient(builder.build(), CircuitBreakerTestSupport.circuitBreakerFactory(circuitBreakerRegistry));
    }

    @Test
    void postsSnakeCaseJsonAndReadsResult() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.front_image_base64").value("FRONT"))
                .andExpect(jsonPath("$.qr_side").value("FRONT"))
                .andExpect(jsonPath("$.retake_count").value(2))
                .andRespond(withSuccess("{\"decision\":\"AUTO_OK\",\"card_type\":\"CCCD_CHIP_2021\",\"field_from_future\":1}",
                        MediaType.APPLICATION_JSON));

        DocumentResult result = client.analyzeDocument(request());

        assertThat(result.decision()).isEqualTo("AUTO_OK");
        assertThat(result.cardType()).isEqualTo("CCCD_CHIP_2021");
        server.verify();
    }

    @Test
    void imageRejectedByAiKeepsHttpStatus() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> client.analyzeDocument(request()))
                .isInstanceOfSatisfying(AiEkycException.class, e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void busyAiKeepsHttpStatus() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.analyzeDocument(request()))
                .isInstanceOfSatisfying(AiEkycException.class, e -> assertThat(e.getStatus()).isEqualTo(503));
    }

    @Test
    void unreachableAiHasNoResponseStatus() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new ConnectException("Connection refused");
        });

        assertThatThrownBy(() -> client.analyzeDocument(request()))
                .isInstanceOfSatisfying(AiEkycException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(AiEkycClient.NO_RESPONSE_STATUS));
    }

    @Test
    void openCircuitFailsFastWithNoResponseStatus() {
        circuitBreakerRegistry.circuitBreaker(AiEkycClient.CIRCUIT_BREAKER).transitionToForcedOpenState();

        assertThatThrownBy(() -> client.analyzeDocument(request()))
                .isInstanceOfSatisfying(AiEkycException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(AiEkycClient.NO_RESPONSE_STATUS));
        server.verify();
    }

    private static DocumentRequest request() {
        return new DocumentRequest("FRONT", "BACK", "001095012345|...", "FRONT", "IN_APP_CAMERA", "IN_APP_CAMERA", 2);
    }
}
