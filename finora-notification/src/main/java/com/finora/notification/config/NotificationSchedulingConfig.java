package com.finora.notification.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật các worker nền (gửi push, đọc receipt, event chờ người nhận, nhắc lịch). {@code finora.notification.workers-enabled
 * =false} chỉ dùng trong test để gọi worker trực tiếp với đồng hồ kiểm soát được.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "finora.notification", name = "workers-enabled", havingValue = "true",
        matchIfMissing = true)
public class NotificationSchedulingConfig {
}
