package com.finora.loan.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình relay Kafka → HTTPS tới CIC Node; mặc định tắt để fail-closed. */
@ConfigurationProperties(prefix = "finora.cic.delinquency-relay")
public record CicDelinquencyRelayProperties(
        boolean enabled,
        String baseUrl,
        String internalApiKey,
        Duration connectTimeout,
        Duration readTimeout,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        double multiplier,
        String topic,
        String groupId,
        String dltTopic
) {
    public CicDelinquencyRelayProperties {
        baseUrl = textOrDefault(baseUrl, "https://cic-service-node.vercel.app");
        internalApiKey = internalApiKey == null ? "" : internalApiKey.trim();
        connectTimeout = positiveOrDefault(connectTimeout, Duration.ofSeconds(3));
        readTimeout = positiveOrDefault(readTimeout, Duration.ofSeconds(15));
        maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
        initialBackoff = positiveOrDefault(initialBackoff, Duration.ofSeconds(1));
        maxBackoff = positiveOrDefault(maxBackoff, Duration.ofSeconds(30));
        multiplier = multiplier <= 1.0d ? 2.0d : multiplier;
        topic = textOrDefault(topic, "finora.loan.delinquency-changed");
        groupId = textOrDefault(groupId, "loan-cic-delinquency-relay");
        dltTopic = textOrDefault(dltTopic, topic + ".cic.DLT");

        if (enabled && internalApiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "finora.cic.delinquency-relay.internal-api-key không được để trống khi relay được bật");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("finora.cic.delinquency-relay.max-attempts phải >= 1");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException(
                    "finora.cic.delinquency-relay.max-backoff phải >= initial-backoff");
        }
    }

    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static Duration positiveOrDefault(Duration value, Duration defaultValue) {
        return value == null || value.isNegative() || value.isZero() ? defaultValue : value;
    }
}
