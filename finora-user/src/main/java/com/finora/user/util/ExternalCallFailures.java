package com.finora.user.util;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Phân loại lỗi mà fallback của circuit breaker (Spring Cloud CircuitBreaker + Resilience4j) nhận được khi gọi hệ thống
 * ngoài, để từng client ném lại đúng exception mà nơi gọi đã bắt từ trước khi có circuit breaker.
 */
public final class ExternalCallFailures {

    private ExternalCallFailures() {
    }

    /**
     * Lỗi thật của lời gọi: bỏ lớp {@link ExecutionException}/{@link CompletionException} mà luồng chạy riêng của circuit
     * breaker có thể bọc ngoài. Luồng gọi bị ngắt khi đang chờ ({@link InterruptedException}) thì đặt lại cờ ngắt.
     */
    public static Throwable cause(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (current instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return current;
    }

    /**
     * Không nhận được phản hồi HTTP: lỗi mạng hoặc quá thời gian chờ của HTTP client ({@link ResourceAccessException}),
     * time limiter cắt ({@link TimeoutException}), circuit breaker đang mở ({@link CallNotPermittedException}) hoặc luồng
     * gọi bị ngắt khi đang chờ.
     */
    public static boolean isUnavailable(Throwable cause) {
        return cause instanceof ResourceAccessException
                || cause instanceof TimeoutException
                || cause instanceof CallNotPermittedException
                || cause instanceof InterruptedException;
    }

    /**
     * Cho client vốn để lỗi của {@code RestClient} đi thẳng tới nơi gọi: lỗi của RestClient ném nguyên vẹn; circuit breaker
     * mở, time limiter cắt hoặc luồng bị ngắt thành {@link ResourceAccessException} như khi mất kết nối.
     *
     * @param system tên hệ thống ngoài, chỉ dùng trong thông điệp lỗi
     */
    public static RuntimeException asRestClientException(Throwable failure, String system) {
        Throwable cause = cause(failure);
        if (cause instanceof RestClientException restClientException) {
            return restClientException;
        }
        if (isUnavailable(cause)) {
            ResourceAccessException unavailable = new ResourceAccessException(
                    "Không gọi được " + system + ": " + cause.getClass().getSimpleName());
            unavailable.initCause(cause);
            return unavailable;
        }
        return unchecked(cause);
    }

    /**
     * Lỗi không thuộc các nhóm trên: lỗi runtime ném nguyên vẹn, {@link Error} ném lại ngay; lỗi checked khác (RestClient
     * không ném) bọc vào {@link IllegalStateException}.
     */
    public static RuntimeException unchecked(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Lời gọi hệ thống ngoài lỗi: " + cause.getClass().getSimpleName(), cause);
    }
}
