package com.finora.user.util;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalCallFailuresTest {

    @Test
    void causeUnwrapsExecutionAndCompletionWrappers() {
        HttpServerErrorException busy = HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "busy",
                HttpHeaders.EMPTY, new byte[0], null);

        assertThat(ExternalCallFailures.cause(new ExecutionException(new CompletionException(busy)))).isSameAs(busy);
    }

    @Test
    void interruptedWaitRestoresInterruptFlagAndCountsAsUnavailable() {
        try {
            Throwable cause = ExternalCallFailures.cause(new InterruptedException());

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(ExternalCallFailures.isUnavailable(cause)).isTrue();
        } finally {
            // Dọn cờ ngắt để không ảnh hưởng test khác chạy cùng luồng.
            Thread.interrupted();
        }
    }

    @Test
    void openCircuitAndTimeoutBecomeResourceAccessForRestClientCallers() {
        CallNotPermittedException open = CallNotPermittedException.createCallNotPermittedException(
                CircuitBreaker.ofDefaults("keycloak-admin"));
        HttpClientErrorException unauthorized = HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                HttpHeaders.EMPTY, new byte[0], null);

        assertThat(ExternalCallFailures.asRestClientException(open, "Keycloak"))
                .isInstanceOf(ResourceAccessException.class)
                .hasCause(open);
        assertThat(ExternalCallFailures.asRestClientException(new TimeoutException(), "Keycloak"))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(ExternalCallFailures.asRestClientException(new ExecutionException(unauthorized), "Keycloak"))
                .isSameAs(unauthorized);
    }

    @Test
    void otherFailuresAreRethrownUnchanged() {
        IllegalArgumentException bug = new IllegalArgumentException("x");
        OutOfMemoryError error = new OutOfMemoryError("y");

        assertThat(ExternalCallFailures.unchecked(bug)).isSameAs(bug);
        assertThat(ExternalCallFailures.unchecked(new Exception("checked"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ExternalCallFailures.unchecked(error)).isSameAs(error);
    }
}
