package com.finora.loan.config;

import com.finora.common.logging.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Thiết lập trace ID ngay đầu request để response và log có cùng mã đối chiếu.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TraceIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{16,64}");
    private static final String UNKNOWN_ROUTE = "UNRESOLVED";

    private final Supplier<String> traceIdGenerator;

    public TraceIdFilter() {
        this(() -> UUID.randomUUID().toString());
    }

    TraceIdFilter(Supplier<String> traceIdGenerator) {
        this.traceIdGenerator = traceIdGenerator;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startedAtNanos = System.nanoTime();
        String previousTraceId = MDC.get(TraceContext.MDC_KEY);
        String traceId = resolveTraceId(request.getHeader(TraceContext.HEADER_NAME));

        request.setAttribute(TraceContext.REQUEST_ATTRIBUTE, traceId);
        response.setHeader(TraceContext.HEADER_NAME, traceId);
        MDC.put(TraceContext.MDC_KEY, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Ghi lần cuối để response lỗi do tầng sau tạo ra vẫn giữ đúng trace ID ban đầu.
            response.setHeader(TraceContext.HEADER_NAME, traceId);
            logCompletion(request, response, startedAtNanos);
            restoreMdc(previousTraceId);
        }
    }

    private String resolveTraceId(String candidate) {
        if (candidate != null && SAFE_TRACE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return traceIdGenerator.get();
    }

    private void logCompletion(HttpServletRequest request, HttpServletResponse response, long startedAtNanos) {
        Object routeAttribute = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = routeAttribute == null ? UNKNOWN_ROUTE : routeAttribute.toString();
        long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
        int status = response.getStatus();

        if (status >= 500) {
            log.warn("HTTP request hoàn tất: method={}, route={}, status={}, durationMs={}",
                    request.getMethod(), route, status, durationMs);
        } else {
            log.info("HTTP request hoàn tất: method={}, route={}, status={}, durationMs={}",
                    request.getMethod(), route, status, durationMs);
        }
    }

    private void restoreMdc(String previousTraceId) {
        if (previousTraceId == null) {
            MDC.remove(TraceContext.MDC_KEY);
        } else {
            MDC.put(TraceContext.MDC_KEY, previousTraceId);
        }
    }
}
