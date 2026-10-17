package com.finora.loan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "finora.ai.credit")
public record AiCreditProperties(
        String baseUrl,
        String modelVersion,
        Duration connectTimeout,
        Duration readTimeout,
        int maxAttempts,
        Duration retryBackoff,
        int workerBatchSize,
        Duration processingLease,
        /**
         * Mã nội bộ gửi trong header X-Internal-Token khi gọi POST /api/v1/ai/credit/explain.
         * Phải trùng AI_INTERNAL_TOKEN của finora-ai; rỗng thì không gửi và AI trả 401.
         */
        String internalToken
) {
    public AiCreditProperties {
        baseUrl = requireText(baseUrl, "base-url");
        modelVersion = requireText(modelVersion, "model-version");
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(30) : retryBackoff;
        workerBatchSize = workerBatchSize <= 0 ? 10 : Math.min(workerBatchSize, 50);
        processingLease = processingLease == null ? Duration.ofMinutes(2) : processingLease;
        internalToken = internalToken == null ? "" : internalToken.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("finora.ai.credit." + field + " không được để trống");
        }
        return value.trim();
    }
}
