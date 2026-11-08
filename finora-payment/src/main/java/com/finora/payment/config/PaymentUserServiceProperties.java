package com.finora.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * finora-user, nơi Payment đọc họ tên và trạng thái eKYC khi liên kết ngân hàng (BL-001).
 *
 * @param baseUrl        gốc của finora-user (không có {@code /api/v1})
 * @param connectTimeout thời gian chờ mở kết nối
 * @param readTimeout    thời gian chờ phản hồi
 */
@ConfigurationProperties(prefix = "finora.payment.user-service")
public record PaymentUserServiceProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public PaymentUserServiceProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8085" : baseUrl.trim();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
    }
}
