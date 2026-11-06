package com.finora.loan.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.messaging.KafkaDeadLetterTopic;
import com.finora.common.messaging.KafkaErrorHandlers;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.loan.exception.LoanBusinessException;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Retry hữu hạn; contract/validation lỗi đi thẳng DLT để không poison consumer group. */
@Configuration
public class LoanKafkaConsumerConfiguration {

    /** Thử lại 2 lần cách 1 giây; DLT là {@code <topic>.DLT} cùng partition với record gốc. */
    @Bean
    DefaultErrorHandler loanKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        return KafkaErrorHandlers.deadLetterErrorHandler(kafkaTemplate, new FixedBackOff(1_000L, 2L),
                KafkaDeadLetterTopic.suffixKeepPartition(".DLT"),
                IllegalArgumentException.class, LoanBusinessException.class);
    }

    /** Bộ đọc event chung của finora-common (không tự khai bean); dùng ObjectMapper của Spring như consumer cũ. */
    @Bean
    KafkaEventReader kafkaEventReader(ObjectMapper objectMapper) {
        return new KafkaEventReader(objectMapper);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler loanKafkaErrorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(loanKafkaErrorHandler);
        return factory;
    }
}
