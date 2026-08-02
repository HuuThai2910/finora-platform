package com.finora.common.logging;

import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;

/**
 * Tên contract và thao tác đọc trace ID dùng chung giữa filter, security handler và exception handler.
 */
public final class TraceContext {

    public static final String HEADER_NAME = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    public static final String REQUEST_ATTRIBUTE = TraceContext.class.getName() + ".traceId";

    private TraceContext() {
    }

    public static Optional<String> currentTraceId() {
        return Optional.ofNullable(MDC.get(MDC_KEY)).filter(value -> !value.isBlank());
    }

    /**
     * Handler vẫn cần một mã đối chiếu khi service chưa cài HTTP trace filter.
     * Hàm chỉ trả mã mới, không ghi vào MDC để tránh để lại ThreadLocal ngoài vòng đời filter.
     */
    public static String currentTraceIdOrCreate() {
        return currentTraceId().orElseGet(() -> UUID.randomUUID().toString());
    }
}
