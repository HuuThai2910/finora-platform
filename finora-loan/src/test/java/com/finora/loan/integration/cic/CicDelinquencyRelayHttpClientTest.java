package com.finora.loan.integration.cic;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.config.CicDelinquencyRelayProperties;
import com.finora.loan.messaging.consumer.KafkaEventEnvelope;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CicDelinquencyRelayHttpClientTest {
    private static final String BASE_URL = "http://cic.test";
    private static final String KEY = "test-internal-key";

    private MockRestServiceServer server;
    private CicDelinquencyRelayHttpClient client;
    private KafkaEventEnvelope envelope;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        CicDelinquencyRelayProperties properties = properties();

        CircuitBreakerFactory factory = mock(CircuitBreakerFactory.class);
        CircuitBreaker breaker = mock(CircuitBreaker.class);
        when(factory.create("cic-delinquency")).thenReturn(breaker);
        when(breaker.run(any(Supplier.class), any(Function.class))).thenAnswer(invocation -> {
            Supplier supplier = invocation.getArgument(0);
            Function<Throwable, ?> fallback = invocation.getArgument(1);
            try {
                return supplier.get();
            } catch (Throwable throwable) {
                return fallback.apply(throwable);
            }
        });

        client = new CicDelinquencyRelayHttpClient(builder.build(), properties, factory);
        envelope = new KafkaEventEnvelope(
                UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001"),
                "2026-10-03T03:00:00Z",
                1,
                new ObjectMapper().createObjectNode().put("borrowerId", "42"));
    }

    @Test
    void sendsInternalKeyAndAcceptsQueuedResponse() {
        server.expect(requestTo(BASE_URL + CicDelinquencyRelayHttpClient.PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(CicDelinquencyRelayHttpClient.INTERNAL_KEY_HEADER, KEY))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        client.relay(envelope);

        server.verify();
    }

    @Test
    void mapsBadRequestToPermanentFailure() {
        server.expect(requestTo(BASE_URL + CicDelinquencyRelayHttpClient.PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.relay(envelope))
                .isInstanceOf(CicDelinquencyRelayException.Permanent.class)
                .hasMessageContaining("HTTP 400");
    }

    @Test
    void mapsServerFailureToRetryableFailure() {
        server.expect(requestTo(BASE_URL + CicDelinquencyRelayHttpClient.PATH))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.relay(envelope))
                .isInstanceOf(CicDelinquencyRelayException.Temporary.class)
                .hasMessageContaining("HTTP 503");
    }

    private static CicDelinquencyRelayProperties properties() {
        return new CicDelinquencyRelayProperties(
                true, BASE_URL, KEY, Duration.ofSeconds(1), Duration.ofSeconds(2), 3,
                Duration.ofMillis(10), Duration.ofSeconds(1), 2.0,
                "finora.loan.delinquency-changed", "loan-cic-test",
                "finora.loan.delinquency-changed.cic.DLT");
    }
}
