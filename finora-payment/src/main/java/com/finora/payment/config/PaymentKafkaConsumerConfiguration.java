package com.finora.payment.config;

import com.finora.common.exception.BusinessException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

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
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception exception) -> {
                    // Không log payload: chỉ vị trí record và loại lỗi gốc.
                    log.error("Event Payment vào DLT: topic={}, partition={}, offset={}, exception={}",
                            record.topic(), record.partition(), record.offset(), rootType(exception));
                    return new TopicPartition(record.topic() + deadLetterSuffix, -1);
                });
        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval.toMillis(), 2.0);
        backOff.setMaxInterval(maxInterval.toMillis());
        backOff.setMaxElapsedTime(maxElapsed.toMillis());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class, BusinessException.class);
        return handler;
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

    private static String rootType(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName();
    }
}
