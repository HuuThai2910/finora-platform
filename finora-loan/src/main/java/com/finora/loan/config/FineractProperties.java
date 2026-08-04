package com.finora.loan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "finora.fineract")
public record FineractProperties(
        String baseUrl,
        String tenantId,
        String username,
        String password,
        String previewClientExternalId,
        Duration connectTimeout,
        Duration readTimeout,
        String productConfigVersion,
        int maxAttempts,
        Duration retryBackoff,
        int retryBatchSize
) {
    public FineractProperties {
        baseUrl = requireText(baseUrl, "baseUrl");
        tenantId = requireText(tenantId, "tenantId");
        username = requireText(username, "username");
        previewClientExternalId = previewClientExternalId == null || previewClientExternalId.isBlank()
                ? "FINORA-PREVIEW-CLIENT"
                : previewClientExternalId.trim();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
        productConfigVersion = productConfigVersion == null ? "FINERACT_PRODUCT_V1" : productConfigVersion;
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(10) : retryBackoff;
        retryBatchSize = retryBatchSize <= 0 ? 10 : Math.min(retryBatchSize, 50);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("finora.fineract." + field + " không được để trống");
        }
        return value.trim();
    }
}
