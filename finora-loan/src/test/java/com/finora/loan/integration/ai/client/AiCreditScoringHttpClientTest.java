package com.finora.loan.integration.ai.client;

import com.finora.loan.config.AiCreditProperties;
import com.finora.loan.integration.ai.contract.AiCreditScoreRequest;
import com.finora.loan.integration.ai.contract.AiCreditScoreResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiCreditScoringHttpClientTest {

    private static final String EXPLAIN_URL = "http://ai.test/api/v1/ai/credit/explain";
    private static final String VALID_RESPONSE = """
            {"pd_probability":0.12,"risk_score":72,"evaluation_score":80.5,"credit_grade":"B",
             "decision":"APPROVED","dien_giai":{},"giai_thich_mo_hinh":{},"rule_trace":[],
             "rejection_reasons":[],"review_reasons":[],"model_version":"17.0.0",
             "decision_policy_version":"CREDIT_POLICY_V1"}
            """;

    private final AiCreditScoringHttpClient client = new AiCreditScoringHttpClient(null, null, null);

    @Test
    void shouldMapDirectTimeLimiterTimeoutAsRetryable() {
        AiCreditIntegrationException result = client.mapException(
                new TimeoutException("TimeLimiter 'ai-credit' recorded a timeout")
        );

        assertTimeoutIsRetryable(result);
    }

    @Test
    void shouldMapWrappedTimeLimiterTimeoutAsRetryable() {
        AiCreditIntegrationException result = client.mapException(
                new CompletionException(new TimeoutException("TimeLimiter 'ai-credit' recorded a timeout"))
        );

        assertTimeoutIsRetryable(result);
    }

    @Test
    void sendsInternalTokenHeaderWhenConfigured() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiCreditScoringHttpClient httpClient = new AiCreditScoringHttpClient(
                builder.build(), properties("ma-noi-bo-123"), passThroughCircuitBreaker());
        server.expect(requestTo(EXPLAIN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "ma-noi-bo-123"))
                .andExpect(header("X-Request-Id", "req-1"))
                .andRespond(withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON));

        AiCreditScoreResponse response = httpClient.score(request(), "req-1");

        assertThat(response.creditGrade()).isEqualTo("B");
        server.verify();
    }

    @Test
    void omitsInternalTokenHeaderWhenNotConfigured() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiCreditScoringHttpClient httpClient = new AiCreditScoringHttpClient(
                builder.build(), properties("  "), passThroughCircuitBreaker());
        server.expect(requestTo(EXPLAIN_URL))
                .andExpect(headerDoesNotExist("X-Internal-Token"))
                .andRespond(withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON));

        httpClient.score(request(), "req-2");

        server.verify();
    }

    @Test
    void shouldMapRejectedInternalTokenAsNonRetryableConfigError() {
        AiCreditIntegrationException result = client.mapException(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                "{\"code\":\"INTERNAL_TOKEN_INVALID\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        assertThat(result.getCode()).isEqualTo("AI_INTERNAL_AUTH_REJECTED");
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void shouldStillMapOtherClientErrorsAsRequestRejected() {
        AiCreditIntegrationException result = client.mapException(HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        assertThat(result.getCode()).isEqualTo("AI_REQUEST_REJECTED");
        assertThat(result.isRetryable()).isFalse();
    }

    private void assertTimeoutIsRetryable(AiCreditIntegrationException result) {
        assertThat(result.getCode()).isEqualTo("AI_TIMEOUT");
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getCause()).isInstanceOf(TimeoutException.class);
    }

    private static AiCreditProperties properties(String internalToken) {
        return new AiCreditProperties("http://ai.test", "17.0.0", null, null, 0, null, 0, null, internalToken);
    }

    /** Circuit breaker chạy thẳng supplier: test này chỉ quan tâm request gửi đi. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CircuitBreakerFactory<?, ?> passThroughCircuitBreaker() {
        CircuitBreakerFactory factory = mock(CircuitBreakerFactory.class);
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(factory.create("ai-credit")).thenReturn(circuitBreaker);
        when(circuitBreaker.run(any(Supplier.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        return factory;
    }

    private static AiCreditScoreRequest request() {
        return new AiCreditScoreRequest(
                30, "5 years", new BigDecimal("300000000"), new BigDecimal("50000000"), "RENT",
                "debt_consolidation", new BigDecimal("14.5"), 12, "Verified", new BigDecimal("15.5"),
                new BigDecimal("4500000"), "DECLINING_BALANCE", null, "27");
    }
}
