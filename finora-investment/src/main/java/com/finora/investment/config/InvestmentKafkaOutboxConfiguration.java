package com.finora.investment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.messaging.producer.InvestmentKafkaOutboxPublisher;
import com.finora.investment.service.outbox.InvestmentOutboxPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
public class InvestmentKafkaOutboxConfiguration {

    @Bean
    InvestmentOutboxPublisher investmentOutboxPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            InvestmentKafkaProperties properties
    ) {
        return new InvestmentKafkaOutboxPublisher(kafkaTemplate, objectMapper, properties);
    }
}
