package com.finora.loan.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Policy relay outbox độc lập transport; Kafka adapter chỉ hoạt động khi được bật rõ ràng. */
@ConfigurationProperties(prefix = "finora.loan.outbox")
public record OutboxProperties(
        int batchSize,
        int maxAttempts,
        Duration retryBackoff,
        Duration maximumBackoff,
        Duration processingLease
) {
    public OutboxProperties {
        batchSize = batchSize == 0 ? 100 : batchSize;
        maxAttempts = maxAttempts == 0 ? 10 : maxAttempts;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(5) : retryBackoff;
        maximumBackoff = maximumBackoff == null ? Duration.ofMinutes(5) : maximumBackoff;
        processingLease = processingLease == null ? Duration.ofMinutes(2) : processingLease;
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("outbox.batchSize phải từ 1 đến 1000");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("outbox.maxAttempts phải từ 1 đến 100");
        }
        if (retryBackoff == null || retryBackoff.isZero() || retryBackoff.isNegative()) {
            throw new IllegalArgumentException("outbox.retryBackoff phải dương");
        }
        if (maximumBackoff == null || maximumBackoff.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException("outbox.maximumBackoff không được nhỏ hơn retryBackoff");
        }
        if (processingLease == null || processingLease.isZero() || processingLease.isNegative()) {
            throw new IllegalArgumentException("outbox.processingLease phải dương");
        }
    }
}
