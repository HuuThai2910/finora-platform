package com.finora.loan.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "finora.signature.provider", havingValue = "VNPT_SMART_CA")
public class SmartCaClientConfig {

    @Bean("smartCaRestClient")
    RestClient smartCaRestClient(SignatureProviderProperties properties, RestClient.Builder builder) {
        SignatureProviderProperties.VnptSmartCa smartCa = properties.vnptSmartCa();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(smartCa.connectTimeout());
        requestFactory.setReadTimeout(smartCa.readTimeout());
        return builder
                .baseUrl(normalizeBaseUrl(smartCa.baseUrl().toString()))
                .requestFactory(requestFactory)
                .build();
    }

    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
