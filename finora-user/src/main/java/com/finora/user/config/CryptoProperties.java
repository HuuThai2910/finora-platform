package com.finora.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình khóa mã hóa — dùng cho HMAC lookup và AES-GCM encrypt/decrypt PII.
 * Giá trị phải được inject từ biến môi trường hoặc vault, không hardcode.
 */
@ConfigurationProperties(prefix = "finora.crypto")
public record CryptoProperties(
        String hmacSecret,
        String aesSecret
) {
}
