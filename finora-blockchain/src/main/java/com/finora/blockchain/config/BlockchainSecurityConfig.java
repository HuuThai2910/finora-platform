package com.finora.blockchain.config;

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

/**
 * B54: finora-common kéo Spring Security vào Blockchain nhưng service chưa cấu hình resource server, nên mọi endpoint
 * rơi vào user Basic sinh ngẫu nhiên của Spring Boot (health 401, explorer không dùng được token Keycloak). Explorer
 * proof chỉ dành cho ADMIN (dữ liệu vận hành, có ID khoản vay); health mở cho kiểm tra sống.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class BlockchainSecurityConfig {

    private final DualBearerTokenResolver bearerTokenResolver;
    private final KeycloakJwtAuthenticationConverter keycloakRoleConverter;

    @Bean
    SecurityFilterChain blockchainSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(blockchainCorsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/blockchain/health").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/blockchain/proofs/**", "/api/v1/blockchain/proofs").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRoleConverter))
                        .bearerTokenResolver(bearerTokenResolver))
                .build();
    }

    @Bean
    CorsConfigurationSource blockchainCorsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Cùng mẫu origin với Payment để web quản trị local gọi thẳng service khi không qua Gateway.
        configuration.setAllowedOriginPatterns(List.of(
                "http://localhost:*", "http://127.0.0.1:*", "http://192.168.*.*:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
