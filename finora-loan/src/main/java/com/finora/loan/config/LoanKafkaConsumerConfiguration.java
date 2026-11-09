package com.finora.loan.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.messaging.KafkaDeadLetterTopic;
import com.finora.common.messaging.KafkaErrorHandlers;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.profile.BorrowerIdentityUnavailableException;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/** Retry hữu hạn; contract/validation lỗi đi thẳng DLT để không poison consumer group. */
@Configuration
public class LoanKafkaConsumerConfiguration {

    /**
     * Thử lại 2 lần cách 1 giây; DLT là {@code <topic>.DLT} cùng partition với record gốc.
     *
     * <p>Riêng lỗi chưa đọc được danh tính bên vay khi lập hợp đồng ({@link BorrowerIdentityUnavailableException}) thử lại
     * theo {@link #borrowerIdentityBackOff()}: User khởi động lại hay Keycloak chậm thường kéo dài hơn 2 giây, và đưa
     * {@code LoanFullyFunded} vào DLT nghĩa là hồ sơ đủ vốn mà không có hợp đồng.</p>
     */
    @Bean
    DefaultErrorHandler loanKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DefaultErrorHandler handler = KafkaErrorHandlers.deadLetterErrorHandler(kafkaTemplate,
                new FixedBackOff(1_000L, 2L), KafkaDeadLetterTopic.suffixKeepPartition(".DLT"),
                IllegalArgumentException.class, LoanBusinessException.class);
        handler.setBackOffFunction((record, exception) -> hasCause(exception, BorrowerIdentityUnavailableException.class)
                ? borrowerIdentityBackOff()
                : null);
        return handler;
    }

    /**
     * 5 giây, nhân đôi, tối đa 60 giây mỗi lần, dừng sau khoảng 10 phút rồi vào DLT. Mỗi khoảng chờ dưới
     * {@code max.poll.interval.ms} mặc định (5 phút) nên consumer không bị rebalance; trong lúc chờ chỉ partition của
     * topic {@code LoanFullyFunded} này dừng lại.
     */
    static BackOff borrowerIdentityBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(5_000L, 2.0);
        backOff.setMaxInterval(60_000L);
        backOff.setMaxElapsedTime(600_000L);
        return backOff;
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                return false;
            }
        }
        return false;
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
