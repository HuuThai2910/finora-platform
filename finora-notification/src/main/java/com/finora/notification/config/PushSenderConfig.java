package com.finora.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.environment.SimulatedSettingsCheck;
import com.finora.notification.integration.push.ExpoPushSender;
import com.finora.notification.integration.push.LoggingPushSender;
import com.finora.notification.integration.push.PushSender;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Chọn push provider theo cấu hình (D62). Mặc định {@code log}: giả lập, không cần EAS project hay thiết bị thật.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class PushSenderConfig {

    static final String PROVIDER_PROPERTY = "finora.notification.push.provider";

    @Bean
    PushSender pushSender(NotificationProperties properties, ObjectMapper mapper, RestClient.Builder builder) {
        PushProviderType provider = properties.push().provider();
        if (provider == PushProviderType.EXPO) {
            NotificationProperties.Expo expo = properties.push().expo();
            log.info("Push provider: EXPO, baseUrl={}, accessToken={}", expo.baseUrl(),
                    expo.hasAccessToken() ? "configured" : "none");
            return new ExpoPushSender(expoClient(builder, expo), mapper);
        }
        log.warn("Push provider: {} — push KHÔNG được gửi tới thiết bị thật (chỉ ghi log/không tạo lần gửi)", provider);
        return new LoggingPushSender();
    }

    /** Cổng môi trường D37: {@code staging}/{@code production} không được chạy với push giả lập. */
    @Bean
    SimulatedSettingsCheck notificationPushSimulatedSettingsCheck() {
        return environment -> {
            String value = environment.getProperty(PROVIDER_PROPERTY, "log").trim().toLowerCase(Locale.ROOT);
            return "log".equals(value)
                    ? List.of(PROVIDER_PROPERTY + "=log (push giả lập, không tới thiết bị)")
                    : List.of();
        };
    }

    static RestClient expoClient(RestClient.Builder builder, NotificationProperties.Expo expo) {
        // Timeout bắt buộc với dependency ngoài: Expo treo không được giữ worker gửi push mãi.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) expo.connectTimeout().toMillis());
        factory.setReadTimeout((int) expo.readTimeout().toMillis());
        return applyExpoDefaults(builder.clone(), expo).requestFactory(factory).build();
    }

    /** Base URL và header chung; tách riêng để test gắn mock server mà vẫn kiểm đúng header xác thực. */
    static RestClient.Builder applyExpoDefaults(RestClient.Builder builder, NotificationProperties.Expo expo) {
        builder.baseUrl(expo.baseUrl()).defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (expo.hasAccessToken()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + expo.accessToken());
        }
        return builder;
    }
}
