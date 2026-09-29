package com.finora.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finora.payment.zalopay")
public record ZaloPayProperties(
        String baseUrl,
        String appId,
        String key1,
        String key2,
        String callbackUrl,
        String redirectUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
    public ZaloPayProperties {
        baseUrl = textOr(baseUrl, "https://sb-openapi.zalopay.vn");
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }

    public int requiredAppId() {
        requireConfigured();
        try {
            return Integer.parseInt(appId);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("ZALOPAY_APP_ID phải là số nguyên", exception);
        }
    }

    public void requireConfigured() {
        if (blank(appId) || blank(key1) || blank(key2) || blank(callbackUrl)) {
            throw new IllegalStateException(
                    "ZALOPAY_APP_ID, ZALOPAY_KEY1, ZALOPAY_KEY2 và ZALOPAY_CALLBACK_URL là bắt buộc");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String textOr(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }
}
