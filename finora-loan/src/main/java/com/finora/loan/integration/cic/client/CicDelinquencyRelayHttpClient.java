package com.finora.loan.integration.cic.client;

import com.finora.common.messaging.KafkaEventEnvelope;
import com.finora.loan.config.CicDelinquencyRelayProperties;
import com.finora.loan.integration.cic.CicDelinquencyRelayException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/** Gửi nguyên envelope v1 sang CIC; không log payload, khoá nội bộ hoặc thông tin tín dụng. */
@Component
@ConditionalOnProperty(prefix = "finora.cic.delinquency-relay", name = "enabled", havingValue = "true")
@Slf4j
public class CicDelinquencyRelayHttpClient {
    static final String PATH = "/api/v1/internal/loan-delinquency-events";
    static final String INTERNAL_KEY_HEADER = "X-Finora-Internal-Key";

    private final RestClient restClient;
    private final CicDelinquencyRelayProperties cicDelinquencyRelayProperties;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public CicDelinquencyRelayHttpClient(
            @Qualifier("cicDelinquencyRestClient") RestClient restClient,
            CicDelinquencyRelayProperties cicDelinquencyRelayProperties,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory
    ) {
        this.restClient = restClient;
        this.cicDelinquencyRelayProperties = cicDelinquencyRelayProperties;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    public void relay(KafkaEventEnvelope envelope) {
        StopWatch watch = new StopWatch();
        watch.start();
        try {
            circuitBreakerFactory.create("cic-delinquency").run(
                    () -> {
                        restClient.post().uri(PATH)
                                .header(INTERNAL_KEY_HEADER, cicDelinquencyRelayProperties.internalApiKey())
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(envelope)
                                .retrieve()
                                .toBodilessEntity();
                        return null;
                    },
                    throwable -> {
                        throw mapException(throwable);
                    });
        } finally {
            watch.stop();
            log.info("Kết thúc relay CIC: eventId={}, latencyMs={}",
                    envelope.eventId(), watch.getTotalTimeMillis());
        }
    }

    CicDelinquencyRelayException mapException(Throwable throwable) {
        Throwable cause = knownCause(throwable);
        if (cause instanceof CicDelinquencyRelayException relayException) {
            return relayException;
        }
        if (cause instanceof RestClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            boolean retryable = status == 408 || status == 429 || status >= 500;
            String message = "CIC từ chối LoanDelinquencyChanged với HTTP " + status;
            return retryable
                    ? new CicDelinquencyRelayException.Temporary(message, responseException)
                    : new CicDelinquencyRelayException.Permanent(message, responseException);
        }
        if (cause instanceof CallNotPermittedException) {
            return new CicDelinquencyRelayException.Temporary("Circuit CIC đang mở", cause);
        }
        if (cause instanceof ResourceAccessException || cause instanceof TimeoutException
                || connectionLost(throwable)) {
            return new CicDelinquencyRelayException.Temporary("Không thể kết nối CIC", cause);
        }
        return new CicDelinquencyRelayException.Temporary("Lỗi không xác định khi relay tới CIC", cause);
    }

    private static Throwable knownCause(Throwable throwable) {
        Throwable current = throwable;
        Throwable deepest = throwable;
        while (current != null) {
            deepest = current;
            if (current instanceof CicDelinquencyRelayException
                    || current instanceof RestClientResponseException
                    || current instanceof CallNotPermittedException
                    || current instanceof ResourceAccessException
                    || current instanceof TimeoutException) {
                return current;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return deepest;
    }

    private static boolean connectionLost(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof IOException) return true;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }
}
