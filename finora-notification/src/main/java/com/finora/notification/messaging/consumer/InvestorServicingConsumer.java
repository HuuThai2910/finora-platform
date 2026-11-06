package com.finora.notification.messaging.consumer;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code InvestorNoteServicingChanged.v1} từ Investment. Giữ đúng consumer group đã đăng ký
 * ({@code notification-investor-servicing}) để offset cũ còn hiệu lực, không đọc lại toàn bộ topic khi nâng cấp.
 */
@Component
@RequiredArgsConstructor
public class InvestorServicingConsumer {

    private final KafkaEventProcessor kafkaEventProcessor;

    @KafkaListener(id = "notification-investor-servicing",
            groupId = "${finora.notification.kafka.investor-servicing-group:notification-investor-servicing}",
            topics = "${finora.notification.kafka.note-servicing-changed-topic:finora.investment.note-servicing-changed}")
    public void consume(ConsumerRecord<String, String> record) {
        kafkaEventProcessor.process("notification-investor-servicing", record);
    }
}
