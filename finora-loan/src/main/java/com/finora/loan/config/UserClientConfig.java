package com.finora.loan.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Client HTTP tới finora-user, chỉ tạo khi provider hồ sơ người vay là {@code user}. */
@Configuration
@ConditionalOnProperty(name = "finora.borrower-profile.provider", havingValue = "user")
@EnableConfigurationProperties(UserServiceProperties.class)
public class UserClientConfig {

    @Bean
    @Qualifier("userRestClient")
    RestClient userRestClient(UserServiceProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
