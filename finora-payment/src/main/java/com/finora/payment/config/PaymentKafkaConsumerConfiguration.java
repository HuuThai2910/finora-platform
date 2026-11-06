package com.finora.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.BusinessException;
import com.finora.common.messaging.KafkaDeadLetterTopic;
import com.finora.common.messaging.KafkaErrorHandlers;
import com.finora.common.messaging.KafkaEventPublisher;
import com.finora.common.messaging.KafkaEventReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

/**
 * Xử lý lỗi của consumer Payment ({@code DisbursementRequested}, {@code LoanDisbursed}, {@code NoteOwnershipChanged}),
 * sửa B58 (D79). Trước đây dùng error handler mặc định của Spring Kafka: record lỗi thử 10 lần liền rồi bị bỏ qua, chỉ còn
 * log, nên yêu cầu giải ngân hay tài khoản thu nợ có thể mất lặng lẽ. Cùng cách với Blockchain (B52) và Notification (B62):
 * <ul>
 *   <li>Contract/validation sai ({@link IllegalArgumentException}, gồm JSON hỏng và envelope/header lệch) và từ chối nghiệp
 *       vụ ({@link BusinessException}): không thử lại, vào DLT ngay để điều tra; partition chạy tiếp.</li>
 *   <li>Lỗi hạ tầng (PostgreSQL tạm mất, khóa xung đột, kết quả giải ngân chưa có): thử lại lùi dần 1 giây tới tối đa 30
 *       giây mỗi lần (dưới {@code max.poll.interval.ms}), trong khoảng 3 phút, rồi mới vào DLT. Không bỏ qua để đi tiếp.</li>
 *   <li>DLT tên {@code <topic>.payment.DLT}: topic nguồn dùng chung với Investment/Blockchain/Notification (ví dụ
 *       {@code finora.loan.disbursed}) nên không trộn lỗi của Payment vào {@code <topic>.DLT} của họ. Partition để producer
 *       tự chọn vì DLT tự tạo chỉ có một partition.</li>
 * </ul>
 * Record trong DLT giữ nguyên envelope gốc (cùng {@code eventId}); consumer ghi {@code payment_processed_events} cùng
 * transaction với tác dụng nên phát lại không tạo tác dụng thứ hai (runbook mục 3.4).
 */
@Slf4j
@Configuration
public class PaymentKafkaConsumerConfiguration {

    @Bean
    DefaultErrorHandler paymentKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
            @Value("${finora.payment.kafka.dead-letter-suffix:.payment.DLT}") String deadLetterSuffix,
            @Value("${finora.payment.kafka.retry-initial-interval:1s}") Duration initialInterval,
            @Value("${finora.payment.kafka.retry-max-interval:30s}") Duration maxInterval,
            @Value("${finora.payment.kafka.retry-max-elapsed:3m}") Duration maxElapsed) {
        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval.toMillis(), 2.0);
        backOff.setMaxInterval(maxInterval.toMillis());
        backOff.setMaxElapsedTime(maxElapsed.toMillis());
        return KafkaErrorHandlers.deadLetterErrorHandler(kafkaTemplate, backOff,
                KafkaDeadLetterTopic.suffixAnyPartition(deadLetterSuffix),
                // Không log payload: chỉ vị trí record và loại lỗi gốc.
                (record, exception) -> log.error(
                        "Event Payment vào DLT: topic={}, partition={}, offset={}, exception={}",
                        record.topic(), record.partition(), record.offset(),
                        KafkaErrorHandlers.rootCauseSimpleName(exception)),
                IllegalArgumentException.class, BusinessException.class);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler paymentKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(paymentKafkaErrorHandler);
        return factory;
    }

    /** Đọc và kiểm envelope/header event nhận; dùng ObjectMapper của Spring nên field lạ trong data bị bỏ qua. */
    @Bean
    KafkaEventReader kafkaEventReader(ObjectMapper objectMapper) {
        return new KafkaEventReader(objectMapper);
    }

    /** Dựng envelope + header và gửi event outbox của Payment ({@code PaymentOutboxPublisher}). */
    @Bean
    KafkaEventPublisher kafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        return new KafkaEventPublisher(kafkaTemplate, objectMapper);
    }
}
