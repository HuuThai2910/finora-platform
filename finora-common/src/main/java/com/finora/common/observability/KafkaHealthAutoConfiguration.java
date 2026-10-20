package com.finora.common.observability;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Đăng ký health {@code kafka} (W3E, D56) cho service có spring-kafka và actuator. Spring Boot 3.2 không có Kafka health
 * indicator sẵn. Tắt theo service bằng {@code management.health.kafka.enabled=false} (ví dụ Blockchain khi listener tắt).
 *
 * <p>Điều kiện cần actuator đặt ở mức class: method chỉ được đọc khi class qua điều kiện, nên service không có actuator
 * không phải nạp annotation của actuator. Không dùng class {@code @Configuration} lồng: service quét
 * {@code com.finora.common} nên class lồng sẽ bị component scan nhận như cấu hình thường và điều kiện
 * {@code @ConditionalOnBean} chạy trước {@code KafkaAutoConfiguration}, luôn sai.</p>
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@ConditionalOnClass(name = {
        "org.springframework.kafka.core.KafkaAdmin",
        "org.springframework.boot.actuate.health.HealthIndicator"
})
@EnableConfigurationProperties(KafkaHealthProperties.class)
public class KafkaHealthAutoConfiguration {

    @Bean
    @ConditionalOnBean(KafkaAdmin.class)
    @ConditionalOnEnabledHealthIndicator("kafka")
    @ConditionalOnMissingBean(name = "kafkaHealthIndicator")
    KafkaClusterHealthIndicator kafkaHealthIndicator(KafkaAdmin kafkaAdmin, KafkaHealthProperties properties) {
        int timeoutMillis = Math.toIntExact(properties.timeout().toMillis());
        return new KafkaClusterHealthIndicator(() -> {
            // Dùng đúng bootstrap/security của KafkaAdmin, chỉ rút ngắn timeout để health không treo 30 giây mặc định.
            Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
            config.put(AdminClientConfig.CLIENT_ID_CONFIG, "finora-kafka-health");
            config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMillis);
            config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMillis);
            return Admin.create(config);
        }, properties.timeout(), properties.cacheTtl(), Clock.systemUTC());
    }
}
