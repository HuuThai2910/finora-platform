package com.finora.investment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.messaging.KafkaEventPublisher;
import com.finora.investment.messaging.producer.InvestmentKafkaOutboxPublisher;
import com.finora.investment.service.outbox.InvestmentOutboxPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
public class InvestmentKafkaOutboxConfiguration {

    @Bean
    KafkaEventPublisher kafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        return new KafkaEventPublisher(kafkaTemplate, objectMapper);
    }

    @Bean
    InvestmentOutboxPublisher investmentOutboxPublisher(
            KafkaEventPublisher kafkaEventPublisher,
            InvestmentKafkaProperties properties
    ) {
        return new InvestmentKafkaOutboxPublisher(kafkaEventPublisher, properties);
    }
}
