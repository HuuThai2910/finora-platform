package com.finora.payment.integration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;

import java.time.Duration;

/**
 * {@code CircuitBreakerFactory} dựng như khi Payment chạy thật: lời gọi chạy trên thread khác dưới time limiter, không
 * có bulkhead (resilience4j-bulkhead không nằm trên classpath). Để test phân loại lỗi của client đi qua đúng chuỗi
 * decorator đó. Ngưỡng mặc định của resilience4j (cửa sổ 100 lời gọi) nên circuit không mở giữa chừng; mỗi lần gọi tạo
 * registry mới để trạng thái circuit không rò giữa các test.
 */
public final class TestCircuitBreakerFactory {

    private TestCircuitBreakerFactory() {
    }

    public static Resilience4JCircuitBreakerFactory create() {
        return create(Duration.ofSeconds(35));
    }

    /** Như {@link #create()} với thời gian của time limiter tùy chọn, để test lời gọi bị time limiter cắt. */
    public static Resilience4JCircuitBreakerFactory create(Duration timeLimiterTimeout) {
        return new Resilience4JCircuitBreakerFactory(CircuitBreakerRegistry.ofDefaults(),
                TimeLimiterRegistry.of(TimeLimiterConfig.custom().timeoutDuration(timeLimiterTimeout).build()),
                null, new Resilience4JConfigurationProperties());
    }
}
