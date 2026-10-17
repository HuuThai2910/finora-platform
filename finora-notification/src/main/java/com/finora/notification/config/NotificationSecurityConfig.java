package com.finora.notification.config;

import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@RequiredArgsConstructor
public class NotificationSecurityConfig {
    public static final String INTERNAL_SEND_AUTHORITY = "notification:internal:send";

    private final DualBearerTokenResolver bearerTokenResolver;
    private final KeycloakJwtAuthenticationConverter converter;

    @Bean
    SecurityFilterChain notificationSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // D36: chỉ service account của finora-user (client role notification:internal:send).
                        .requestMatchers("/api/internal/notifications/**").hasAuthority(INTERNAL_SEND_AUTHORITY)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .bearerTokenResolver(bearerTokenResolver))
                .build();
    }
}
