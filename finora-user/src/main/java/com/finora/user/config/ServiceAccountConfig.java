package com.finora.user.config;

import com.finora.common.security.service.ClientCredentialsTokenProvider;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Danh tính của finora-user khi gọi AI (OCR CCCD) và Notification (email OTP, cảnh báo) — D34. Dùng client
 * {@code finora-user-client} đã có cho Keycloak Admin; service account được gán thêm client role
 * {@code ai:ekyc:ocr} và {@code notification:internal:send}. Đăng ký, quên mật khẩu, đăng nhập sai chưa có JWT người
 * dùng nên lời gọi phải mang token của service.
 */
@Configuration
public class ServiceAccountConfig {

    @Bean
    ClientCredentialsTokenProvider userServiceTokenProvider(RestClient.Builder builder,
            KeycloakAdminProperties keycloak) {
        String tokenUrl = keycloak.getServerUrl() + "/realms/" + keycloak.getRealm()
                + "/protocol/openid-connect/token";
        return new ClientCredentialsTokenProvider(builder, Clock.systemUTC(), tokenUrl, keycloak.getClientId(),
                keycloak.getClientSecret());
    }
}
