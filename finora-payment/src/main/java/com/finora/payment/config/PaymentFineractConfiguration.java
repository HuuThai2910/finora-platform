package com.finora.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({PaymentFineractProperties.class, EarlySettlementPolicyProperties.class})
public class PaymentFineractConfiguration {
    @Bean("paymentFineractRestClient")
    RestClient paymentFineractRestClient(PaymentFineractProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return builder.baseUrl(properties.baseUrl()).requestFactory(factory)
                .defaultHeader("Fineract-Platform-TenantId", properties.tenantId()).build();
    }
}
