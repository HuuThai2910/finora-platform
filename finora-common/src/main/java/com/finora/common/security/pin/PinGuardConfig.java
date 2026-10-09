package com.finora.common.security.pin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.time.Duration;

/**
 * Đăng ký {@link PinTokenService} và interceptor kiểm {@link RequirePin} cho mọi
 * service quét gói {@code com.finora.common}.
 * <p>
 * {@code finora.pin.token-secret} phải giống nhau ở finora-user và các service
 * có endpoint gắn PIN.
 */
@Configuration
public class PinGuardConfig implements WebMvcConfigurer {

    private final PinTokenService pinTokenService;

    public PinGuardConfig(
            @Value("${finora.pin.token-secret:}") String secret,
            @Value("${finora.pin.token-ttl:PT3M}") Duration ttl) {
        this.pinTokenService = new PinTokenService(secret, ttl, Clock.systemUTC());
    }

    @Bean
    public PinTokenService pinTokenService() {
        return pinTokenService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequirePinInterceptor(pinTokenService));
    }
}
