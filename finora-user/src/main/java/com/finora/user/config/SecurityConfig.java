package com.finora.user.config;

import com.finora.user.security.DualBearerTokenResolver;
import com.finora.user.security.KeycloakJwtAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Cấu hình Spring Security 6 — stateless REST API với Keycloak JWT.
 * <p>
 * CSRF tắt vì API stateless, cookie access_token dùng SameSite=Lax chống CSRF ở tầng trình duyệt.
 * CORS cho phép frontend dev (localhost:3000, 5173) và API gateway (8080).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final DualBearerTokenResolver dualBearerTokenResolver;
    private final KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Tắt CSRF — API stateless, không dùng session
                .csrf(csrf -> csrf.disable())

                // CORS — cho phép frontend dev server và API gateway
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Session stateless — mỗi request tự mang token
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Phân quyền endpoint
                .authorizeHttpRequests(auth -> auth
                        // Endpoint công khai — không cần token
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/register",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password"
                        ).permitAll()
                        // Swagger UI và OpenAPI docs
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Actuator health check cho monitoring/k8s
                        .requestMatchers("/actuator/health").permitAll()
                        // Mọi endpoint còn lại yêu cầu xác thực
                        .anyRequest().authenticated()
                )

                // OAuth2 Resource Server — xác thực JWT từ Keycloak
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                // Dùng DualBearerTokenResolver để hỗ trợ cả header và cookie
                                .jwtAuthenticationConverter(keycloakJwtAuthenticationConverter)
                        )
                        .bearerTokenResolver(dualBearerTokenResolver)
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:3000",   // Next.js / React dev server
                "http://localhost:5173",   // Vite dev server
                "http://localhost:8080"    // API Gateway
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);  // Cần thiết cho cookie-based auth

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
