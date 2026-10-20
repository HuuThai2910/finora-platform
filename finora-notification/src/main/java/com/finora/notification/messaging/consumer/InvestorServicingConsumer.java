package com.finora.notification.messaging.consumer;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * {@code InvestorNoteServicingChanged.v1} từ Investment. Giữ đúng consumer group đã đăng ký
 * ({@code notification-investor-servicing}) để offset cũ còn hiệu lực, không đọc lại toàn bộ topic khi nâng cấp.
 */
@Component
@RequiredArgsConstructor
public class InvestorServicingConsumer {

    private final KafkaEventProcessor processor;

    @KafkaListener(id = "notification-investor-servicing",
            groupId = "${finora.notification.kafka.investor-servicing-group:notification-investor-servicing}",
            topics = "${finora.notification.kafka.note-servicing-changed-topic:finora.investment.note-servicing-changed}")
    public void consume(@Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(name = "finora-event-id", required = false) String eventId,
            @Header(name = "finora-event-type", required = false) String eventType,
            @Header(name = "finora-event-version", required = false) String eventVersion) {
        processor.process("notification-investor-servicing", topic, partition, offset, payload, eventId, eventType,
                eventVersion);
    }
}
