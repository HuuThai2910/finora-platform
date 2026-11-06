package com.finora.user.config;

import com.finora.common.security.service.ClientCredentialsTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * Danh tính của finora-user khi gọi AI (OCR CCCD) và Notification (email OTP, cảnh báo) — D34. Dùng client
 * {@code finora-user-client} đã có cho Keycloak Admin; service account được gán thêm client role
 * {@code ai:ekyc:ocr} và {@code notification:internal:send}. Đăng ký, quên mật khẩu, đăng nhập sai chưa có JWT người
 * dùng nên lời gọi phải mang token của service.
 */
@Configuration
public class ServiceAccountConfig {

    /** Tên bean interceptor gắn token service account; client AI và Notification lấy đúng bean này qua {@code @Qualifier}. */
    public static final String BEARER_INTERCEPTOR = "serviceAccountBearerInterceptor";

    /**
     * Gắn {@code Authorization: Bearer} (token service account của finora-user) cho {@code RestClient} gọi AI và
     * Notification (D34). Chỉ client của hai service đó nhận interceptor này qua {@code @Qualifier}; Spring Boot không tự
     * gắn {@link ClientHttpRequestInterceptor} vào {@code RestClient.Builder}, nên token không bị gửi tới CIC hay dịch vụ
     * bên ngoài. Không lấy được token thì lời gọi lỗi như mất kết nối; bên nhận trả 401 thì token đang cache bị bỏ để lần
     * sau lấy mới.
     */
    @Bean(BEARER_INTERCEPTOR)
    ClientHttpRequestInterceptor serviceAccountBearerInterceptor(ClientCredentialsTokenProvider userServiceTokenProvider) {
        return userServiceTokenProvider.bearerInterceptor();
    }

    @Bean
    ClientCredentialsTokenProvider userServiceTokenProvider(RestClient.Builder builder,
            KeycloakAdminProperties keycloak) {
        String tokenUrl = keycloak.getServerUrl() + "/realms/" + keycloak.getRealm()
                + "/protocol/openid-connect/token";
        return new ClientCredentialsTokenProvider(builder, Clock.systemUTC(), tokenUrl, keycloak.getClientId(),
                keycloak.getClientSecret());
    }
}
