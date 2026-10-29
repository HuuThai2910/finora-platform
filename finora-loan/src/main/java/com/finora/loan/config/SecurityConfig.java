package com.finora.loan.config;

import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.common.security.ops.OpsFunction;
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
 * <p>Endpoint {@code /admin/**} được kiểm ngay tại tầng URL theo quyền chức năng
 * ({@link OpsFunction}): GET cần quyền Xem, lệnh ghi cần quyền Thao tác, ADMIN luôn qua.
 * API quản trị chưa gắn chức năng thì chỉ ADMIN gọi được. Các endpoint còn lại chỉ cần
 * đăng nhập, vì quyền trên từng hồ sơ do domain tự kiểm tra qua {@code requireOwner}.</p>
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
                        // Metric vận hành (số sự cố, hàng đợi) chỉ cho quản trị viên (W3E).
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Phân quyền nhân viên theo chức năng; ADMIN luôn qua (canView/canAct có sẵn ROLE_ADMIN).
                        // Hồ sơ vay: xem danh sách, chi tiết duyệt, giải thích và các lần chấm điểm.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/admin/loan-applications",
                                "/api/v1/admin/loan-applications/*/review",
                                "/api/v1/admin/loan-applications/*/explanation",
                                "/api/v1/admin/loan-applications/*/assessments",
                                "/api/v1/admin/loan-applications/*/assessments/*")
                        .hasAnyAuthority(OpsFunction.LOAN_APPLICATION.canView())
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/admin/loan-applications/*/approve",
                                "/api/v1/admin/loan-applications/*/reject",
                                "/api/v1/admin/loan-applications/*/scoring-retry")
                        .hasAnyAuthority(OpsFunction.LOAN_APPLICATION.canAct())
                        // Sản phẩm vay: tạo, sửa, đổi trạng thái và đồng bộ core đều là Thao tác.
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/loan-products/**")
                        .hasAnyAuthority(OpsFunction.LOAN_PRODUCT.canView())
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/admin/loan-products",
                                "/api/v1/admin/loan-products/*/activate",
                                "/api/v1/admin/loan-products/*/deactivate",
                                "/api/v1/admin/loan-products/*/archive",
                                "/api/v1/admin/loan-products/*/core-sync")
                        .hasAnyAuthority(OpsFunction.LOAN_PRODUCT.canAct())
                        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/loan-products/*")
                        .hasAnyAuthority(OpsFunction.LOAN_PRODUCT.canAct())
                        // Cơ cấu nợ.
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/loan-reschedule-requests")
                        .hasAnyAuthority(OpsFunction.RESCHEDULE.canView())
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/admin/loan-reschedule-requests/*/approve",
                                "/api/v1/admin/loan-reschedule-requests/*/reject")
                        .hasAnyAuthority(OpsFunction.RESCHEDULE.canAct())
                        // Thu hồi nợ.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/admin/collection-cases",
                                "/api/v1/admin/collection-cases/*/actions")
                        .hasAnyAuthority(OpsFunction.COLLECTION.canView())
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/collection-cases/*/actions")
                        .hasAnyAuthority(OpsFunction.COLLECTION.canAct())
                        // Đối soát & sự cố: projection lệch, saga giải ngân, sự kiện trả nợ bị cách ly.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/admin/loan-servicing-reconciliation",
                                "/api/v1/admin/loan-servicing-reconciliation/incidents",
                                "/api/v1/admin/disbursement-sagas",
                                "/api/v1/admin/repayment-event-quarantine")
                        .hasAnyAuthority(OpsFunction.RECONCILIATION.canView())
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/admin/loan-servicing-reconciliation/*/reconcile",
                                "/api/v1/admin/disbursement-sagas/*/requeue",
                                "/api/v1/admin/repayment-event-quarantine/*/replay")
                        .hasAnyAuthority(OpsFunction.RECONCILIATION.canAct())
                        // Thống kê cho trang Tổng quan.
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/loan-statistics/**")
                        .hasAnyAuthority(OpsFunction.DASHBOARD.canView())
                        // API quản trị chưa gắn chức năng nào: chỉ ADMIN, nhân viên mặc định bị chặn.
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
