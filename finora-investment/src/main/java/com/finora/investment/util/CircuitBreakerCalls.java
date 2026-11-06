package com.finora.investment.util;

import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Chạy lời gọi sang service khác (Payment, User) qua circuit breaker mà client vẫn tự phân loại lỗi gốc như trước khi có
 * circuit breaker.
 */
public final class CircuitBreakerCalls {

    private CircuitBreakerCalls() {
    }

    /**
     * Chạy {@code call} qua circuit breaker và time limiter tên {@code name} (khai ở
     * {@code resilience4j.circuitbreaker.instances} và {@code resilience4j.timelimiter.instances}).
     * <p>
     * Spring Cloud chạy {@code call} trên thread pool riêng, nên bên trong {@code call} không đọc SecurityContext hay dữ
     * liệu gắn với thread của request. Lỗi của lời gọi được ném lại nguyên vẹn để nhánh catch của client xử lý như cũ.
     * Mạch mở ném {@code CallNotPermittedException} mà không gọi ra ngoài; hết giờ ({@code TimeoutException}) hoặc thread
     * gọi bị ngắt được bọc thành {@link IllegalStateException}. Cả ba rơi vào nhánh "không kết nối được" của client: chưa
     * biết bên kia đã làm hay chưa, nên nơi gọi giữ nguyên cách xử lý kết quả chưa chắc chắn.
     */
    public static <T> T run(CircuitBreakerFactory<?, ?> circuitBreakerFactory, String name, Supplier<T> call) {
        return circuitBreakerFactory.create(name).run(call, throwable -> {
            throw rethrowable(throwable);
        });
    }

    private static RuntimeException rethrowable(Throwable throwable) {
        Throwable cause = throwable instanceof ExecutionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof InterruptedException) {
            // Bọc lỗi nhưng giữ cờ ngắt cho thread gọi (ứng dụng đang tắt hoặc request bị huỷ).
            Thread.currentThread().interrupt();
        }
        return new IllegalStateException("Lời gọi chưa hoàn tất: " + cause.getClass().getSimpleName(), cause);
    }
}
