package com.finora.payment.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Liên kết ngân hàng (BL-001): cấu hình và client HTTP tới finora-user. */
@Configuration
@EnableConfigurationProperties({BankLinkProperties.class, PaymentUserServiceProperties.class})
public class PaymentBankLinkConfiguration {

    @Bean
    @Qualifier("paymentUserRestClient")
    RestClient paymentUserRestClient(PaymentUserServiceProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }
}
