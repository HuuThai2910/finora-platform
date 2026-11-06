package com.finora.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.messaging.KafkaDeadLetterTopic;
import com.finora.common.messaging.KafkaErrorHandlers;
import com.finora.common.messaging.KafkaEventReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Xử lý lỗi của consumer Notification (sửa B62). Trước đây không cấu hình gì: Spring thử lại 10 lần liền rồi bỏ qua
 * record (chỉ log), thông báo mất lặng lẽ.
 * <ul>
 *   <li>Payload/contract sai ({@link IllegalArgumentException}, gồm cả người nhận mâu thuẫn): không thử lại, đẩy ngay
 *       vào DLT để điều tra; không bao giờ đoán người nhận.</li>
 *   <li>Lỗi hạ tầng (DB tạm mất, khóa xung đột): thử lại có lùi dần trong khoảng ba phút, mỗi lần chờ tối đa 30 giây
 *       (dưới {@code max.poll.interval.ms}) rồi mới vào DLT. Không bỏ qua event để đi tiếp.</li>
 *   <li>DLT tên {@code <topic>.notification.DLT}: topic nguồn dùng chung với Investment/Payment/Blockchain, DLT
 *       {@code <topic>.DLT} của họ không được lẫn lỗi của Notification. Partition để producer tự chọn vì DLT tự tạo
 *       chỉ có một partition.</li>
 * </ul>
 * Error handler dựng bằng {@link KafkaErrorHandlers} dùng chung; backoff, tên DLT và dòng log khi vào DLT vẫn do
 * Notification chọn như trên.
 */
@Slf4j
@Configuration
public class NotificationKafkaConfig {

    @Bean
    DefaultErrorHandler notificationKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
            NotificationProperties properties) {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(180_000L);
        return KafkaErrorHandlers.deadLetterErrorHandler(kafkaTemplate, backOff,
                KafkaDeadLetterTopic.suffixAnyPartition(properties.kafka().deadLetterSuffix()),
                // Không log payload: chỉ vị trí record và loại lỗi gốc.
                (record, exception) -> log.error(
                        "Event Notification vào DLT: topic={}, partition={}, offset={}, exception={}",
                        record.topic(), record.partition(), record.offset(),
                        KafkaErrorHandlers.rootCauseSimpleName(exception)),
                IllegalArgumentException.class);
    }

    /** Đọc và kiểm envelope/header cho {@code InboundEventReader}, dùng ObjectMapper của Spring Boot. */
    @Bean
    KafkaEventReader kafkaEventReader(ObjectMapper objectMapper) {
        return new KafkaEventReader(objectMapper);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler notificationKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(notificationKafkaErrorHandler);
        return factory;
    }
}
