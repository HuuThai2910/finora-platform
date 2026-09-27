package com.finora.investment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finora.investment.outbox")
public record InvestmentOutboxProperties(
        Duration publisherDelay,
        int batchSize,
        int maxAttempts,
        Duration retryBackoff,
        Duration maximumBackoff,
        Duration processingLease
) {
    public InvestmentOutboxProperties {
        publisherDelay = publisherDelay == null ? Duration.ofSeconds(5) : publisherDelay;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(5) : retryBackoff;
        maximumBackoff = maximumBackoff == null ? Duration.ofMinutes(5) : maximumBackoff;
        processingLease = processingLease == null ? Duration.ofMinutes(2) : processingLease;
        if (batchSize < 1 || batchSize > 500 || maxAttempts < 1) {
            throw new IllegalArgumentException("Cấu hình Investment outbox không hợp lệ");
        }
    }
}
