package com.finora.user.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình kết nối Keycloak Admin REST API — dùng để tạo user, gán role, reset password.
 */
@ConfigurationProperties(prefix = "keycloak.admin")
@Getter
@Setter
@NoArgsConstructor
public class KeycloakAdminProperties {

    private String serverUrl;
    private String realm;
    private String clientId;
    private String clientSecret;
}
