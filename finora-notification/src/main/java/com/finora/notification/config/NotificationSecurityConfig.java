package com.finora.notification.config;

import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.common.security.ops.OpsFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                        // Actuator ngoài health chỉ cho quản trị viên, như các service khác (W3E).
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // D36: chỉ service account của finora-user (client role notification:internal:send).
                        .requestMatchers("/api/internal/notifications/**").hasAuthority(INTERNAL_SEND_AUTHORITY)
                        // R7: vận hành push/event chờ thuộc chức năng Đối soát & sự cố: xem cần quyền Xem, đưa push lỗi
                        // về hàng đợi cần quyền Thao tác (ADMIN luôn qua). Đặt dưới /api/v1/notifications để Gateway
                        // đưa về Notification sẵn có, không để route /api/v1/admin/** của Loan nuốt mất.
                        .requestMatchers(HttpMethod.GET, "/api/v1/notifications/admin/push-deliveries",
                                "/api/v1/notifications/admin/deferred-events")
                                .hasAnyAuthority(OpsFunction.RECONCILIATION.canView())
                        .requestMatchers(HttpMethod.POST, "/api/v1/notifications/admin/push-deliveries/*/requeue")
                                .hasAnyAuthority(OpsFunction.RECONCILIATION.canAct())
                        // Đường dẫn quản trị chưa gán chức năng: chỉ ADMIN (nhân viên bị chặn mặc định).
                        .requestMatchers("/api/v1/notifications/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .bearerTokenResolver(bearerTokenResolver))
                .build();
    }
}
