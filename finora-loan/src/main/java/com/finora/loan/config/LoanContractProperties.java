package com.finora.loan.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Policy local dùng để tạo đúng một phiên bản Contract và giới hạn thời gian chờ ký. */
@ConfigurationProperties(prefix = "finora.loan.contract")
public record LoanContractProperties(
        String decisionPolicyVersion,
        String termsVersion,
        String documentVersion,
        Duration signatureWindow,
        Duration maximumSignatureWindow,
        int expiryBatchSize
) {
    public LoanContractProperties {
        decisionPolicyVersion = requireText(decisionPolicyVersion, "decisionPolicyVersion");
        termsVersion = requireText(termsVersion, "termsVersion");
        documentVersion = requireText(documentVersion, "documentVersion");
        if (signatureWindow == null || signatureWindow.isZero() || signatureWindow.isNegative()) {
            throw new IllegalArgumentException("finora.loan.contract.signatureWindow phải dương");
        }
        if (maximumSignatureWindow == null || maximumSignatureWindow.compareTo(signatureWindow) < 0) {
            throw new IllegalArgumentException("maximumSignatureWindow không được nhỏ hơn signatureWindow");
        }
        if (expiryBatchSize < 1 || expiryBatchSize > 1000) {
            throw new IllegalArgumentException("expiryBatchSize phải từ 1 đến 1000");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("finora.loan.contract." + field + " không được để trống");
        }
        return value.trim();
    }
}
