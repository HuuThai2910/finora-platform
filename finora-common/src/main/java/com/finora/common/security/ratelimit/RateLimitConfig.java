package com.finora.common.security.ratelimit;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Đăng ký giới hạn tần suất {@link RateLimited} cho mọi service quét {@code com.finora.common} (D40).
 * {@code finora.rate-limit.enabled=false} chỉ dùng cho test gọi lặp một endpoint có giới hạn.
 */
@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    private final boolean enabled;
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(Clock.systemUTC());

    public RateLimitConfig(@Value("${finora.rate-limit.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (enabled) {
            registry.addInterceptor(new RateLimitInterceptor(limiter));
        }
    }
}
