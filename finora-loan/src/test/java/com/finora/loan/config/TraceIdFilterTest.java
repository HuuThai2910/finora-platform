package com.finora.loan.config;

import com.finora.common.logging.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private static final String GENERATED_TRACE_ID = "generated-trace-00000001";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void keepsSafeClientTraceIdAndCleansMdc() throws Exception {
        TraceIdFilter filter = new TraceIdFilter(() -> GENERATED_TRACE_ID);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/loans/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String clientTraceId = "client-trace-12345678";
        request.addHeader(TraceContext.HEADER_NAME, clientTraceId);

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(MDC.get(TraceContext.MDC_KEY)).isEqualTo(clientTraceId));

        assertThat(response.getHeader(TraceContext.HEADER_NAME)).isEqualTo(clientTraceId);
        assertThat(request.getAttribute(TraceContext.REQUEST_ATTRIBUTE)).isEqualTo(clientTraceId);
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeTraceId() throws Exception {
        TraceIdFilter filter = new TraceIdFilter(() -> GENERATED_TRACE_ID);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/loans/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(TraceContext.HEADER_NAME, "short");

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        assertThat(response.getHeader(TraceContext.HEADER_NAME)).isEqualTo(GENERATED_TRACE_ID);
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }
}
