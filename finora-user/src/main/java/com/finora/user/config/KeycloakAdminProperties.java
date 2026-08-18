package com.finora.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình kết nối Keycloak Admin REST API — dùng để tạo user, gán role, reset password.
 */
@ConfigurationProperties(prefix = "keycloak.admin")
public record KeycloakAdminProperties(
        String serverUrl,
        String realm,
        String clientId,
        String clientSecret
) {
}
