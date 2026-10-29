package com.finora.investment.config;

import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.common.security.ops.OpsFunction;
import jakarta.servlet.DispatcherType;
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
 * {@code /investments/admin/**} kiểm quyền chức năng ngay tại tầng URL: nhân viên cần quyền Xem/Thao tác
 * {@code ops:*} của màn tương ứng ({@link OpsFunction}), ADMIN luôn qua; đường dẫn quản trị chưa gán chức năng
 * vẫn chỉ ADMIN. Đặt/huỷ lệnh,
 * Auto-Invest và danh mục cá nhân yêu cầu vai trò INVESTOR; quyền trên từng lệnh vẫn do
 * domain kiểm khi so chủ sở hữu, còn eKYC và chặn tự cấp vốn ở {@code InvestorEligibility}.</p>
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
                        // Metric vận hành (sự cố lệnh góp vốn, outbox) chỉ cho quản trị viên (W3E).
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Stream sổ lệnh (SSE) gửi dữ liệu qua async dispatch sau khi request gốc đã
                        // qua xác thực. Không mở thì Spring Security chặn lại lượt dispatch đó khi
                        // response đã commit và stream bị cắt.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Swagger để đối chiếu hợp đồng API khi phát triển.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Gọi vốn & Notes: xem nhà đầu tư/Note; khóa vốn, phát hành Note, đóng listing hết hạn.
                        .requestMatchers(HttpMethod.GET, "/api/v1/investments/admin/listings/*/investors",
                                "/api/v1/investments/admin/commitments/*/notes")
                                .hasAnyAuthority(OpsFunction.FUNDING.canView())
                        .requestMatchers(HttpMethod.POST, "/api/v1/investments/admin/listings/*/finalize",
                                "/api/v1/investments/admin/listings/*/activate-notes",
                                "/api/v1/investments/admin/listings/close-expired")
                                .hasAnyAuthority(OpsFunction.FUNDING.canAct())
                        // Cấu hình gọi vốn: đổi tham số ảnh hưởng mọi khoản lên sàn sau đó.
                        .requestMatchers(HttpMethod.GET, "/api/v1/investments/admin/settings")
                                .hasAnyAuthority(OpsFunction.FUNDING_SETTINGS.canView())
                        .requestMatchers(HttpMethod.PUT, "/api/v1/investments/admin/settings")
                                .hasAnyAuthority(OpsFunction.FUNDING_SETTINGS.canAct())
                        // Đối soát & sự cố: lệnh góp vốn lệch với ví ở Payment.
                        .requestMatchers(HttpMethod.GET, "/api/v1/investments/admin/order-incidents")
                                .hasAnyAuthority(OpsFunction.RECONCILIATION.canView())
                        .requestMatchers(HttpMethod.POST, "/api/v1/investments/admin/order-incidents/*/retry")
                                .hasAnyAuthority(OpsFunction.RECONCILIATION.canAct())
                        // Chợ thứ cấp: sổ lệnh, lịch sử khớp và giấy xác nhận chuyển nhượng.
                        .requestMatchers(HttpMethod.GET, "/api/v1/investments/admin/order-books/summary",
                                "/api/v1/investments/admin/order-books/trades",
                                "/api/v1/investments/admin/transfer-documents",
                                "/api/v1/investments/admin/transfer-documents/*",
                                "/api/v1/investments/admin/transfer-documents/*/pdf")
                                .hasAnyAuthority(OpsFunction.SECONDARY_MARKET.canView())
                        .requestMatchers(HttpMethod.POST, "/api/v1/investments/admin/transfer-documents/*/retry")
                                .hasAnyAuthority(OpsFunction.SECONDARY_MARKET.canAct())
                        .requestMatchers(HttpMethod.GET, "/api/v1/investments/admin/statistics/**")
                                .hasAnyAuthority(OpsFunction.DASHBOARD.canView())
                        // Đường dẫn quản trị chưa gán chức năng: chỉ ADMIN (nhân viên bị chặn mặc định).
                        .requestMatchers("/api/v1/investments/admin/**").hasRole("ADMIN")
                        // Thao tác bỏ tiền và dữ liệu danh mục cá nhân chỉ dành cho nhà đầu tư.
                        // Đọc sàn/sổ lệnh vẫn chỉ cần đăng nhập: web quản trị và người vay cũng xem.
                        .requestMatchers(HttpMethod.POST, "/api/v1/investments/listings/*/orders",
                                "/api/v1/investments/orders/*/cancel",
                                "/api/v1/investments/order-books/*/orders").hasRole("INVESTOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/investments/order-books/orders/*")
                                .hasRole("INVESTOR")
                        .requestMatchers("/api/v1/investments/auto-invest", "/api/v1/investments/auto-invest/**",
                                "/api/v1/investments/portfolio", "/api/v1/investments/commitments",
                                "/api/v1/investments/order-books/orders/mine",
                                "/api/v1/investments/order-books/*/me").hasRole("INVESTOR")
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
     * {@code allowCredentials} vì web gửi token bằng cookie. Dùng mẫu origin để Expo web
     * (cổng bất kỳ) và máy trong LAN gọi thẳng service được.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://192.168.*.*:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
