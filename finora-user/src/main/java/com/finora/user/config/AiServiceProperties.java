package com.finora.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Kết nối nội bộ tới {@code finora-ai} ({@code finora.ai.*}).
 * <p>
 * Route eKYC của AI chỉ nhận request có header {@code X-Internal-Token} bằng {@link #internalToken}
 * (biến {@code AI_INTERNAL_TOKEN}, phải giống nhau ở hai service). Token rỗng chỉ hợp lệ khi AI cũng
 * chạy chế độ dev không kiểm token.
 */
@ConfigurationProperties(prefix = "finora.ai")
@Getter
@Setter
public class AiServiceProperties {

    private String url = "http://localhost:8000";

    private String internalToken = "";

    private Duration connectTimeout = Duration.ofSeconds(3);

    /** Phân tích video liveness chạy tuần tự (semaphore) ở AI nên có thể chờ lâu. */
    private Duration livenessReadTimeout = Duration.ofSeconds(60);

    /** Token đã chuẩn hoá: không bao giờ {@code null} để header luôn được gửi nhất quán. */
    public String internalTokenOrEmpty() {
        return internalToken == null ? "" : internalToken.trim();
    }
}
