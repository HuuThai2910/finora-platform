package com.finora.user.util;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;

import java.time.Duration;

/**
 * Circuit breaker thật của Spring Cloud CircuitBreaker + Resilience4j cho unit test client: chạy lời gọi ở luồng riêng
 * qua time limiter như khi chạy ứng dụng, để test thấy đúng exception mà fallback nhận.
 */
public final class CircuitBreakerTestSupport {

    /** Đủ rộng để máy CI chậm không bị time limiter cắt lời gọi tới {@code MockRestServiceServer}. */
    private static final Duration TEST_TIME_LIMIT = Duration.ofSeconds(10);

    private CircuitBreakerTestSupport() {
    }

    public static Resilience4JCircuitBreakerFactory circuitBreakerFactory() {
        return circuitBreakerFactory(CircuitBreakerRegistry.ofDefaults(), TEST_TIME_LIMIT);
    }

    /** Dùng registry cho trước để test chủ động mở circuit ({@code transitionToForcedOpenState}). */
    public static Resilience4JCircuitBreakerFactory circuitBreakerFactory(CircuitBreakerRegistry circuitBreakerRegistry) {
        return circuitBreakerFactory(circuitBreakerRegistry, TEST_TIME_LIMIT);
    }

    /** Time limiter mặc định {@code timeLimit} để test lời gọi bị cắt vì quá giờ. */
    public static Resilience4JCircuitBreakerFactory circuitBreakerFactory(CircuitBreakerRegistry circuitBreakerRegistry,
            Duration timeLimit) {
        TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(timeLimit)
                .cancelRunningFuture(true)
                .build());
        return new Resilience4JCircuitBreakerFactory(circuitBreakerRegistry, timeLimiterRegistry, null,
                new Resilience4JConfigurationProperties());
    }
}
