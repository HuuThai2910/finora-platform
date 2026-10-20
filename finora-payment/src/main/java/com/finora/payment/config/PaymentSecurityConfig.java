package com.finora.payment.config;

import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Bảo vệ API ví bằng cùng access token Keycloak với Loan và Investment. */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class PaymentSecurityConfig {

    private final DualBearerTokenResolver bearerTokenResolver;
    private final KeycloakJwtAuthenticationConverter keycloakRoleConverter;

    @Bean
    public SecurityFilterChain paymentSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Metric vận hành (hàng đợi đối soát, outbox) chỉ cho quản trị viên (W3E).
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // ZaloPay không có JWT của FINORA; tính xác thực nằm ở HMAC Key2 của callback.
                        .requestMatchers(HttpMethod.POST, "/api/v1/wallets/callbacks/zalopay").permitAll()
                        // Provider payout giả lập (R4): xác thực bằng HMAC trên body, endpoint 404 khi provider không phải mock.
                        .requestMatchers(HttpMethod.POST, "/api/v1/wallets/callbacks/mock-payout").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRoleConverter))
                        .bearerTokenResolver(bearerTokenResolver));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Mẫu origin để Expo web (cổng bất kỳ) và máy trong LAN gọi thẳng service được.
        configuration.setAllowedOriginPatterns(List.of(
                "http://localhost:*", "http://127.0.0.1:*", "http://192.168.*.*:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
