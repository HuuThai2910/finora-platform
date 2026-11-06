package com.finora.investment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.messaging.KafkaDeadLetterTopic;
import com.finora.common.messaging.KafkaErrorHandlers;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.investment.exception.InvestmentDomainException;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Retry lỗi hạ tầng tối đa hai lần; payload/domain lỗi đi DLT để điều tra có kiểm soát. */
@Configuration
public class InvestmentKafkaConsumerConfiguration {

    @Bean
    KafkaEventReader kafkaEventReader(ObjectMapper objectMapper) {
        return new KafkaEventReader(objectMapper);
    }

    /** {@code <topic>.DLT} giữ partition gốc; event sai hợp đồng và lỗi nghiệp vụ vào DLT ngay, không retry. */
    @Bean
    DefaultErrorHandler investmentKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        return KafkaErrorHandlers.deadLetterErrorHandler(kafkaTemplate, new FixedBackOff(1_000L, 2L),
                KafkaDeadLetterTopic.suffixKeepPartition(".DLT"),
                IllegalArgumentException.class, InvestmentDomainException.class);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler investmentKafkaErrorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(investmentKafkaErrorHandler);
        return factory;
    }
}
