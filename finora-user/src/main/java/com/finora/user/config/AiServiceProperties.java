package com.finora.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Kết nối nội bộ tới {@code finora-ai} ({@code finora.ai.*}).
 * <p>
 * Route eKYC của AI chỉ nhận access token service account của finora-user (client role {@code ai:ekyc:ocr},
 * D34/D35); token lấy từ {@code ServiceAccountConfig}, không cấu hình ở đây.
 */
@ConfigurationProperties(prefix = "finora.ai")
@Getter
@Setter
public class AiServiceProperties {

    private String url = "http://localhost:8000";

    private Duration connectTimeout = Duration.ofSeconds(3);

    /** Phân tích video liveness chạy tuần tự (semaphore) ở AI nên có thể chờ lâu. */
    private Duration livenessReadTimeout = Duration.ofSeconds(60);
}
