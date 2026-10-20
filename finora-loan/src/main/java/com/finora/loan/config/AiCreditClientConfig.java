package com.finora.loan.config;

import com.finora.common.security.service.ClientCredentialsTokenProvider;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({AiCreditProperties.class, BorrowerEligibilityProperties.class})
public class AiCreditClientConfig {

    /**
     * Danh tính của Loan khi worker chấm điểm gọi AI (D34): token {@code client_credentials} của
     * {@code finora-loan-client}, mang client role {@code ai:credit:score}. Đồng hồ thật, không phải đồng hồ nghiệp
     * vụ/E2E của Loan, vì AI kiểm hạn token theo giờ thật.
     */
    @Bean
    @Qualifier("loanServiceTokenProvider")
    ClientCredentialsTokenProvider loanServiceTokenProvider(
            RestClient.Builder builder,
            @Value("${finora.ai.credit.service-account.token-url}") String tokenUrl,
            @Value("${finora.ai.credit.service-account.client-id}") String clientId,
            @Value("${finora.ai.credit.service-account.client-secret:}") String clientSecret) {
        return new ClientCredentialsTokenProvider(builder, Clock.systemUTC(), tokenUrl, clientId, clientSecret);
    }

    @Bean
    @Qualifier("aiCreditRestClient")
    RestClient aiCreditRestClient(AiCreditProperties properties, RestClient.Builder builder,
            @Qualifier("loanServiceTokenProvider") ClientCredentialsTokenProvider tokenProvider) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(tokenProvider.bearerInterceptor())
                .build();
    }
}
