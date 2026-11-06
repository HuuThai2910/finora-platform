package com.finora.notification.config;

import com.finora.notification.integration.push.client.PushSender;
import com.finora.notification.integration.push.client.impl.ExpoPushSender;
import com.finora.notification.integration.push.contract.PushMessage;
import com.finora.notification.integration.push.contract.PushOutcome;
import com.finora.notification.integration.push.contract.PushTicket;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JAutoConfiguration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring thật của {@link PushSenderConfig} khi bật Expo: sender nhận {@code CircuitBreakerFactory} của Spring (registry
 * dựng từ {@code application.yml}) và gọi Expo qua circuit {@code expo-push} của registry đó. Circuit được ép mở nên
 * không có request mạng; giá trị cấu hình của circuit được kiểm ở {@code ExpoPushSenderTest}.
 */
class PushSenderConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(applicationYml()))
            .withPropertyValues(PushSenderConfig.PROVIDER_PROPERTY + "=expo")
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    RestClientAutoConfiguration.class, CircuitBreakerAutoConfiguration.class,
                    TimeLimiterAutoConfiguration.class, Resilience4JAutoConfiguration.class))
            .withUserConfiguration(PushSenderConfig.class);

    @Test
    void expoSenderCallsExpoThroughTheCircuitBreakerDeclaredInApplicationYml() {
        contextRunner.run(context -> {
            PushSender pushSender = context.getBean(PushSender.class);
            assertThat(pushSender).isInstanceOf(ExpoPushSender.class);
            context.getBean(CircuitBreakerRegistry.class).circuitBreaker("expo-push").transitionToOpenState();

            assertThat(pushSender.send(List.of(new PushMessage(1, "ExponentPushToken[FAKE-test-token-0001]",
                    "tokenref1", "Trả nợ", "Khoản trả nợ của bạn đã được ghi nhận.", Map.of("notificationId", "n-1"),
                    Duration.ofHours(24)))))
                    .containsExactly(PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "EXPO_CIRCUIT_OPEN"));
        });
    }

    private static PropertySource<?> applicationYml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml")).get(0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
