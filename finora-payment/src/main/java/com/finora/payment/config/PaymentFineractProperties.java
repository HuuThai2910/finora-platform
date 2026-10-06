package com.finora.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finora.payment.fineract")
public record PaymentFineractProperties(String baseUrl, String tenantId, String username,
        String password, Long repaymentPaymentTypeId, Duration connectTimeout, Duration readTimeout) {
    public PaymentFineractProperties {
        baseUrl = text(baseUrl, "baseUrl");
        tenantId = text(tenantId, "tenantId");
        username = text(username, "username");
        password = text(password, "password");
        if (repaymentPaymentTypeId == null || repaymentPaymentTypeId <= 0) {
            throw new IllegalArgumentException("finora.payment.fineract.repaymentPaymentTypeId phải dương");
        }
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("finora.payment.fineract." + field + " không được trống");
        return value.trim();
    }
}
