package com.finora.blockchain.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.BusinessException;
import com.finora.common.messaging.KafkaDeadLetterTopic;
import com.finora.common.messaging.KafkaErrorHandlers;
import com.finora.common.messaging.KafkaEventReader;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * B52: trước đây Blockchain dùng error handler mặc định (thử lại 10 lần liên tiếp rồi bỏ record, chỉ còn một dòng log),
 * nên PostgreSQL tạm lỗi lúc consume làm mất yêu cầu proof. Giờ lỗi hạ tầng thử lại lùi dần ~31 giây rồi vào
 * {@code <topic>.DLT}; lỗi contract/xung đột nội dung vào DLT ngay để điều tra, không chặn partition.
 */
@Configuration
public class BlockchainKafkaConsumerConfiguration {

    @Bean
    DefaultErrorHandler blockchainKafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(5);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(16_000L);
        return KafkaErrorHandlers.deadLetterErrorHandler(kafkaTemplate, backOff,
                KafkaDeadLetterTopic.suffixKeepPartition(".DLT"),
                IllegalArgumentException.class, BusinessException.class);
    }

    /** Đọc và kiểm envelope/header cho {@code MilestoneProofRegistrar}, dùng ObjectMapper của Spring Boot. */
    @Bean
    KafkaEventReader kafkaEventReader(ObjectMapper objectMapper) {
        return new KafkaEventReader(objectMapper);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler blockchainKafkaErrorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(blockchainKafkaErrorHandler);
        return factory;
    }
}
