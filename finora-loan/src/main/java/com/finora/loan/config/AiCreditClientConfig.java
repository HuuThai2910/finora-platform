package com.finora.loan.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({AiCreditProperties.class, BorrowerEligibilityProperties.class})
@Slf4j
public class AiCreditClientConfig {

    @Bean
    @Qualifier("aiCreditRestClient")
    RestClient aiCreditRestClient(AiCreditProperties properties, RestClient.Builder builder) {
        if (properties.internalToken().isEmpty()) {
            // Không chặn khởi động (các luồng khác của Loan vẫn chạy), nhưng mọi lần chấm điểm sẽ
            // bị AI từ chối 401 — báo ngay từ log khởi động để không phải lần từ hồ sơ lỗi.
            log.warn("Chưa đặt AI_INTERNAL_TOKEN: finora-ai sẽ từ chối POST /api/v1/ai/credit/explain của Loan");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
