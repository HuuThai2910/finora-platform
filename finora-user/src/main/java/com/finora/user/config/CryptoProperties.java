package com.finora.user.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình khóa mã hóa — dùng cho HMAC lookup và AES-GCM encrypt/decrypt PII.
 * Giá trị phải được inject từ biến môi trường hoặc vault, không hardcode.
 */
@ConfigurationProperties(prefix = "finora.crypto")
@Getter
@Setter
@NoArgsConstructor
public class CryptoProperties {

    private String hmacSecret;
    private String aesSecret;
}
