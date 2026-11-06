package com.finora.payment.util;

import com.finora.payment.integration.TestCircuitBreakerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lỗi đi qua circuit breaker thật (chạy lời gọi trên thread khác, có time limiter) tới client đúng như trước. */
class CircuitBreakerCallsTest {

    @Test
    void httpErrorReachesTheClientUnchangedSoItsOwnClassificationStillApplies() {
        HttpServerErrorException serverError = new HttpServerErrorException(HttpStatus.BAD_GATEWAY);

        assertThatThrownBy(() -> CircuitBreakerCalls.run(TestCircuitBreakerFactory.create(), "test-dependency",
                "Fineract", () -> {
                    throw serverError;
                }))
                .isSameAs(serverError);
    }

    @Test
    void interruptedWaitIsReportedLikeALostConnectionAndKeepsTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> CircuitBreakerCalls.run(TestCircuitBreakerFactory.create(), "test-dependency",
                    "Fineract", () -> sleepThenAnswer(Duration.ofSeconds(1))))
                    .isInstanceOf(ResourceAccessException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static String sleepThenAnswer(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return "late";
    }
}
