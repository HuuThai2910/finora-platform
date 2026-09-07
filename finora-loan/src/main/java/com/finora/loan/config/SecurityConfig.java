package com.finora.loan.config;

import com.finora.loan.security.DualBearerTokenResolver;
import com.finora.loan.security.KeycloakRoleConverter;
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

import java.util.List;

/**
 * Bảo vệ API của Loan Service bằng access token do Keycloak cấp.
 *
 * <p>Trước đây service tin vào {@code MockCurrentUserProvider} nên mọi hồ sơ vay đều
 * thuộc về một người vay cố định. Từ đây, danh tính lấy từ token: mobile gửi qua
 * header, web quản trị gửi qua cookie, và {@code CurrentUserProvider} đọc claim
 * {@code user_id} để biết hồ sơ thuộc về ai.</p>
 *
 * <p>Endpoint {@code /admin/**} yêu cầu vai trò ADMIN ngay tại tầng URL; các endpoint
 * còn lại chỉ cần đăng nhập, vì quyền trên từng hồ sơ do domain tự kiểm tra qua
 * {@code requireOwner}.</p>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final DualBearerTokenResolver bearerTokenResolver;
    private final KeycloakRoleConverter keycloakRoleConverter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // API stateless, không dùng session nên không có gì để CSRF bảo vệ.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Probe của hạ tầng phải gọi được khi chưa có token.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRoleConverter))
                        .bearerTokenResolver(bearerTokenResolver)
                );

        return http.build();
    }

    /**
     * Cho phép web quản trị chạy ở dev server và request đi qua Gateway. Phải bật
     * {@code allowCredentials} vì web gửi token bằng cookie.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(
                "http://localhost:3000",
                "http://localhost:5173",
                "http://localhost:8080"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
