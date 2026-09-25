package com.finora.blockchain.config;

import com.finora.blockchain.domain.proof.ProofProviderType;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Chính sách ghi proof; mặc định dùng mock và không tự chạy worker. */
@ConfigurationProperties(prefix = "finora.blockchain.proof")
public record ProofSubmissionProperties(
        ProofProviderType provider,
        int batchSize,
        int maxAttempts,
        Duration retryBackoff,
        Duration maximumBackoff,
        Duration processingLease
) {
    public ProofSubmissionProperties {
        provider = provider == null ? ProofProviderType.MOCK : provider;
        batchSize = batchSize == 0 ? 100 : batchSize;
        maxAttempts = maxAttempts == 0 ? 10 : maxAttempts;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(5) : retryBackoff;
        maximumBackoff = maximumBackoff == null ? Duration.ofMinutes(5) : maximumBackoff;
        processingLease = processingLease == null ? Duration.ofMinutes(2) : processingLease;
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("proof.batchSize phải từ 1 đến 1000");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("proof.maxAttempts phải từ 1 đến 100");
        }
        if (retryBackoff.isZero() || retryBackoff.isNegative()) {
            throw new IllegalArgumentException("proof.retryBackoff phải dương");
        }
        if (maximumBackoff.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException("proof.maximumBackoff không được nhỏ hơn retryBackoff");
        }
        if (processingLease.isZero() || processingLease.isNegative()) {
            throw new IllegalArgumentException("proof.processingLease phải dương");
        }
    }
}
