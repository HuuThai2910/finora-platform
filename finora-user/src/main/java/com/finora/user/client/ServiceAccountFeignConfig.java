package com.finora.user.client;

import com.finora.common.security.service.ClientCredentialsTokenProvider;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

/**
 * Gắn token service account của finora-user cho Feign client gọi AI và Notification (D34).
 * <p>
 * Cố ý không đánh dấu {@code @Configuration}: Feign chỉ áp cấu hình này cho client khai báo
 * {@code configuration = ServiceAccountFeignConfig.class}, nên token không bị gửi tới CIC hay dịch vụ bên ngoài.
 */
public class ServiceAccountFeignConfig {

    @Bean
    RequestInterceptor serviceAccountBearer(ClientCredentialsTokenProvider userServiceTokenProvider) {
        return template -> template.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + userServiceTokenProvider.accessToken());
    }
}
