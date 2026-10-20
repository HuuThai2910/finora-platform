package com.finora.common.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.common.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;

class RateLimitInterceptorTest {

    private static final Instant NOW = Instant.parse("2026-10-17T09:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final RateLimitInterceptor interceptor = new RateLimitInterceptor(new InMemoryRateLimiter(new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sixthOcrWithinTenMinutesIsRejectedWithRetryAfter() throws Exception {
        actAs("user-a");
        for (int i = 0; i < 5; i++) {
            assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    handler("ocr"))).isTrue();
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(), response, handler("ocr")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("RATE_LIMITED");
        assertThat(response.getHeader("Retry-After")).isEqualTo("600");
    }

    @Test
    void limitIsPerUserAndResetsAfterTheWindow() throws Exception {
        actAs("user-a");
        for (int i = 0; i < 5; i++) {
            interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("ocr"));
        }
        actAs("user-b");
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                handler("ocr"))).isTrue();

        actAs("user-a");
        now.set(NOW.plus(Duration.ofMinutes(10)));
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                handler("ocr"))).isTrue();
    }

    @Test
    void endpointsWithoutTheAnnotationAreNotCounted() throws Exception {
        actAs("user-a");
        for (int i = 0; i < 50; i++) {
            assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    handler("open"))).isTrue();
        }
    }

    @Test
    void anonymousCallersAreCountedByAddress() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setRemoteAddr("10.0.0.1");
        for (int i = 0; i < 5; i++) {
            interceptor.preHandle(first, new MockHttpServletResponse(), handler("ocr"));
        }
        MockHttpServletRequest other = new MockHttpServletRequest();
        other.setRemoteAddr("10.0.0.2");

        assertThat(interceptor.preHandle(other, new MockHttpServletResponse(), handler("ocr"))).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(first, new MockHttpServletResponse(), handler("ocr")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void expiredWindowsAreDroppedOnceTheTableGrows() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        });
        for (int i = 0; i <= InMemoryRateLimiter.CLEANUP_THRESHOLD; i++) {
            limiter.tryAcquire("k" + i, 1, Duration.ofSeconds(30));
        }
        assertThat(limiter.trackedKeys()).isGreaterThan(InMemoryRateLimiter.CLEANUP_THRESHOLD);

        // Một phút sau mọi cửa sổ 30 giây đã hết: lần gọi kế tiếp dọn sạch trước khi đếm.
        now.set(NOW.plus(Duration.ofMinutes(1)));
        limiter.tryAcquire("fresh", 1, Duration.ofMinutes(1));

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    private static void actAs(String subject) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Sample(), Sample.class.getMethod(name));
    }

    static class Sample {
        @RateLimited(value = "ekyc.ocr", limit = 5, window = "PT10M")
        public void ocr() {
        }

        public void open() {
        }
    }
}
