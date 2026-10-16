package com.finora.loan.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kết nối tới finora-user để đọc tóm tắt eKYC của người vay khi nộp hồ sơ (W2-KYC). Timeout ngắn vì cuộc gọi nằm trong
 * request nộp hồ sơ của người vay; User không phản hồi thì trả {@code 503} để người vay thử lại.
 */
@ConfigurationProperties(prefix = "finora.user")
public record UserServiceProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public UserServiceProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8085" : baseUrl.trim();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
    }
}
