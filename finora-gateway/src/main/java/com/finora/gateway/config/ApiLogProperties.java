package com.finora.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình access log của Gateway.
 *
 * @param enabled tắt thì Gateway không ghi dòng {@code finora.api} nào
 * @param bodyLimit số byte tối đa đọc từ body gửi lên và body lỗi trả về
 * @param loggedBodyLimit số ký tự body gửi lên (đã che) giữ trong một dòng log, để dòng không vượt giới hạn syslog
 */
@ConfigurationProperties(prefix = "finora.api-log")
public record ApiLogProperties(
        boolean enabled,
        int bodyLimit,
        int loggedBodyLimit
) {
}
