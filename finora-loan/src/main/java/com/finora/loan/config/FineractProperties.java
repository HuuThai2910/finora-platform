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
        int retryBatchSize,
        Duration processingLease,
        String productNamespace
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
        productConfigVersion = productConfigVersion == null || productConfigVersion.isBlank()
                ? "FINORA-FINERACT-V4" : productConfigVersion.trim();
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(10) : retryBackoff;
        retryBatchSize = retryBatchSize <= 0 ? 10 : Math.min(retryBatchSize, 50);
        processingLease = processingLease == null ? Duration.ofMinutes(2) : processingLease;
        productNamespace = normalizeNamespace(productNamespace);
        if (processingLease.isZero() || processingLease.isNegative()) {
            throw new IllegalArgumentException("finora.fineract.processingLease phải lớn hơn 0");
        }
    }

    /**
     * Không gian tên của Product FINORA trên Fineract khi nhiều DB Loan dùng chung một Fineract (ví dụ stack E2E cô lập,
     * Wave 2D). Rỗng giữ nguyên externalId/name/shortName hiện hành để mapping và request hash đã có không đổi.
     */
    public static String normalizeNamespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{1,8}")) {
            throw new IllegalArgumentException("finora.fineract.productNamespace chỉ gồm A-Z, 0-9, tối đa 8 ký tự");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("finora.fineract." + field + " không được để trống");
        }
        return value.trim();
    }
}
