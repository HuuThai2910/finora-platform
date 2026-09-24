package com.finora.investment.config;

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
 * Bảo vệ API của Investment Service bằng access token do Keycloak cấp.
 *
 * <p>Trước đây danh tính nhà đầu tư đọc từ header {@code X-Investor-Id} và rơi về
 * INVESTOR-001 khi thiếu, nghĩa là ai cũng đặt được lệnh dưới danh nghĩa người khác.
 * Từ đây danh tính lấy từ token: mobile gửi qua header, web quản trị gửi qua cookie,
 * và {@code CurrentUserProvider} đọc claim {@code user_id} để biết lệnh thuộc về ai.</p>
 *
 * <p>Phân quyền theo ba nhóm. Đọc thị trường mở cho khách chưa đăng nhập vì đó là
 * thông tin niêm yết công khai, không chứa dữ liệu cá nhân người vay (F03 bước 4).
 * {@code /investments/admin/**} yêu cầu vai trò ADMIN ngay tại tầng URL. Phần còn lại
 * — đặt lệnh, huỷ lệnh, xem danh mục — chỉ cần đăng nhập, vì quyền trên từng lệnh do
 * domain tự kiểm tra khi so chủ sở hữu.</p>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final DualBearerTokenResolver bearerTokenResolver;
    private final KeycloakJwtAuthenticationConverter keycloakRoleConverter;

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
                        // Swagger để đối chiếu hợp đồng API khi phát triển.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/investments/admin/**").hasRole("ADMIN")
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
