package com.finora.loan.config;

import com.finora.loan.integration.cic.CicDelinquencyRelayException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.web.client.RestClient;

/** Hạ tầng riêng cho relay CIC để không làm thay đổi retry của các consumer Loan khác. */
@Configuration
@ConditionalOnProperty(prefix = "finora.cic.delinquency-relay", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CicDelinquencyRelayProperties.class)
public class CicDelinquencyRelayConfiguration {

    @Bean
    @Qualifier("cicDelinquencyRestClient")
    RestClient cicDelinquencyRestClient(CicDelinquencyRelayProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    @Bean("cicDelinquencyKafkaErrorHandler")
    DefaultErrorHandler cicDelinquencyKafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            CicDelinquencyRelayProperties properties
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception exception) ->
                        new TopicPartition(properties.dltTopic(), record.partition()));
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(properties.maxAttempts() - 1);
        backOff.setInitialInterval(properties.initialBackoff().toMillis());
        backOff.setMultiplier(properties.multiplier());
        backOff.setMaxInterval(properties.maxBackoff().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                CicDelinquencyRelayException.Permanent.class);
        return handler;
    }

    @Bean("cicDelinquencyKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object, Object> cicDelinquencyKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            @Qualifier("cicDelinquencyKafkaErrorHandler") DefaultErrorHandler errorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
