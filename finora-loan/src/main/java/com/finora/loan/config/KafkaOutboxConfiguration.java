package com.finora.loan.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.messaging.KafkaEventPublisher;
import com.finora.loan.messaging.producer.KafkaOutboxPublisher;
import com.finora.loan.service.outbox.OutboxPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/** Chỉ tạo Kafka publisher khi operator chọn transport rõ ràng; local mặc định không kết nối broker. */
@Configuration
@ConditionalOnProperty(name = "finora.loan.outbox.transport", havingValue = "kafka")
@EnableConfigurationProperties(KafkaPublisherProperties.class)
public class KafkaOutboxConfiguration {

    /** Bộ gửi chung của finora-common (không tự khai bean); dùng ObjectMapper của Spring như bản cũ của Loan. */
    @Bean
    KafkaEventPublisher kafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        return new KafkaEventPublisher(kafkaTemplate, objectMapper);
    }

    @Bean
    OutboxPublisher kafkaOutboxPublisher(
            KafkaEventPublisher kafkaEventPublisher,
            KafkaPublisherProperties properties
    ) {
        return new KafkaOutboxPublisher(kafkaEventPublisher, properties);
    }
}
