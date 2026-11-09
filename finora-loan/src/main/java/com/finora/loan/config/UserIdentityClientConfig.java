package com.finora.loan.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Client HTTP tới API nội bộ của finora-user để đọc danh tính bên vay khi lập hợp đồng. Tách khỏi {@code userRestClient}
 * (chuyển tiếp token người vay, chỉ có khi provider hồ sơ là {@code user}) vì luồng này dùng token service account và bật
 * theo {@code finora.borrower-identity.provider} riêng.
 */
@Configuration
@ConditionalOnProperty(name = "finora.borrower-identity.provider", havingValue = "user", matchIfMissing = true)
@EnableConfigurationProperties(UserServiceProperties.class)
public class UserIdentityClientConfig {

    @Bean
    @Qualifier("userIdentityRestClient")
    RestClient userIdentityRestClient(UserServiceProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
