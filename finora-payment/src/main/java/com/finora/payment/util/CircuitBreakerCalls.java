package com.finora.payment.util;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Gọi hệ thống ngoài qua circuit breaker (resilience4j, cấu hình {@code resilience4j.circuitbreaker.instances.*} và
 * {@code resilience4j.timelimiter.instances.*} cùng tên) mà client vẫn tự phân loại lỗi như trước khi có circuit
 * breaker.
 * <p>
 * Lỗi của chính lời gọi HTTP được ném lại nguyên vẹn. Circuit đang mở (lời gọi không được gửi đi), quá thời gian của
 * time limiter hoặc bị ngắt khi đang chờ thì báo bằng {@link ResourceAccessException} như mất kết nối, để nơi gọi xử lý
 * như hệ thống ngoài đang sập: lệnh có thể đã gửi vẫn là "chưa biết kết quả", không bao giờ thành "bị từ chối".
 */
public final class CircuitBreakerCalls {

    private CircuitBreakerCalls() {
    }

    /**
     * Chạy {@code call} qua circuit breaker {@code circuitBreakerName}; {@code dependencyName} chỉ dùng trong thông báo
     * lỗi. Spring Cloud chạy lời gọi trên thread khác nên {@code call} không được dựa vào dữ liệu theo thread.
     */
    public static <T> T run(CircuitBreakerFactory<?, ?> circuitBreakerFactory, String circuitBreakerName,
            String dependencyName, Supplier<T> call) {
        return circuitBreakerFactory.create(circuitBreakerName).run(call,
                throwable -> rethrow(throwable, dependencyName));
    }

    private static <T> T rethrow(Throwable throwable, String dependencyName) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof CallNotPermittedException) {
            throw unavailable("Tạm dừng gọi " + dependencyName + " vì circuit breaker đang mở", cause);
        }
        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof TimeoutException) {
            throw unavailable(dependencyName + " không phản hồi trong thời gian cho phép", cause);
        }
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        throw unavailable("Không nhận được phản hồi của " + dependencyName, cause);
    }

    /** Bỏ lớp bọc của Future khi chạy bất đồng bộ để lấy đúng exception của lời gọi. */
    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static ResourceAccessException unavailable(String message, Throwable cause) {
        ResourceAccessException exception = new ResourceAccessException(message);
        exception.initCause(cause);
        return exception;
    }
}
