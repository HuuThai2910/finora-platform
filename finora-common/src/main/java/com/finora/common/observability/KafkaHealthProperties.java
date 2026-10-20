package com.finora.common.observability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình health {@code kafka} (W3E, D56).
 *
 * @param timeout  thời gian tối đa chờ broker trả lời {@code describeCluster}
 * @param cacheTtl thời gian dùng lại kết quả lần kiểm trước, để endpoint health bị gọi dồn không tạo nhiều admin client
 */
@ConfigurationProperties(prefix = "finora.operations.health.kafka")
public record KafkaHealthProperties(
        @DefaultValue("3s") Duration timeout,
        @DefaultValue("30s") Duration cacheTtl
) {

    public KafkaHealthProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("finora.operations.health.kafka.timeout phải lớn hơn 0");
        }
        cacheTtl = cacheTtl == null || cacheTtl.isNegative() ? Duration.ZERO : cacheTtl;
    }
}
