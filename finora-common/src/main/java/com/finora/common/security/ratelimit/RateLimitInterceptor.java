package com.finora.common.security.ratelimit;

import com.finora.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Áp {@link RateLimited} trước khi controller chạy, nên request bị chặn không gọi provider hay ghi dữ liệu. */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final InMemoryRateLimiter limiter;

    public RateLimitInterceptor(InMemoryRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimited rule = method.getMethodAnnotation(RateLimited.class);
        if (rule == null) {
            return true;
        }
        InMemoryRateLimiter.Decision decision = limiter.tryAcquire(
                rule.value() + "|" + caller(request), rule.limit(), Duration.parse(rule.window()));
        if (decision.allowed()) {
            return true;
        }
        long seconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Bạn thao tác quá nhanh, vui lòng thử lại sau " + seconds + " giây");
    }

    private static String caller(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.getToken().getSubject() != null) {
            return "sub:" + jwt.getToken().getSubject();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
