package com.finora.user.integration.loganalytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.common.exception.BusinessException;
import com.finora.user.config.ActivityLogProperties;
import com.finora.user.util.CircuitBreakerTestSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LogAnalyticsClientTest {

    private static final String TOKEN_URL = "http://imds/token";
    private static final String QUERY_URL = "https://api.loganalytics.io/v1/workspaces/ws-1/query";

    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    private MockRestServiceServer server;
    private LogAnalyticsClient client;
    private ActivityLogProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ActivityLogProperties();
        properties.setWorkspaceId("ws-1");
        properties.setTokenUrl(TOKEN_URL);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LogAnalyticsClient(builder.build(), properties,
                CircuitBreakerTestSupport.circuitBreakerFactory(circuitBreakerRegistry));
    }

    @Test
    void queriesWithManagedIdentityTokenAndMapsColumns() {
        long expires = Instant.now().plusSeconds(3600).getEpochSecond();
        server.expect(once(), requestTo(TOKEN_URL))
                .andExpect(header("Metadata", "true"))
                .andRespond(withSuccess("{\"access_token\":\"tok\",\"expires_on\":\"" + expires + "\"}",
                        MediaType.APPLICATION_JSON));
        String table = "{\"tables\":[{\"name\":\"PrimaryResult\",\"columns\":[{\"name\":\"path\"},{\"name\":\"status\"}],"
                + "\"rows\":[[\"/api/v1/loans\",401]]}]}";
        server.expect(requestTo(QUERY_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(content().json("{\"query\":\"Syslog | take 1\"}"))
                .andRespond(withSuccess(table, MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL)).andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess(table, MediaType.APPLICATION_JSON));

        List<Map<String, JsonNode>> rows = client.query("Syslog | take 1");
        client.query("Syslog | take 1");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("path").asText()).isEqualTo("/api/v1/loans");
        assertThat(rows.get(0).get("status").asInt()).isEqualTo(401);
        server.verify();
    }

    @Test
    void reportsNotConfiguredAndRejectedQueries() {
        properties.setWorkspaceId("");
        assertThatThrownBy(() -> client.query("x"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ACTIVITY_LOG_NOT_CONFIGURED"));

        properties.setWorkspaceId("ws-1");
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"tok\",\"expires_on\":\"9999999999\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> client.query("x"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ACTIVITY_LOG_QUERY_FAILED"));
    }

    @Test
    void openCircuitReportsUnavailableWithoutQuerying() {
        server.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"tok\",\"expires_on\":\"9999999999\"}",
                        MediaType.APPLICATION_JSON));
        circuitBreakerRegistry.circuitBreaker(LogAnalyticsClient.CIRCUIT_BREAKER).transitionToForcedOpenState();

        assertThatThrownBy(() -> client.query("x"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("ACTIVITY_LOG_UNAVAILABLE");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                });
        server.verify();
    }
}
