package com.finora.notification.messaging.consumer;

import com.finora.notification.service.intake.InboundEvent;
import com.finora.notification.service.intake.IntakeResult;
import com.finora.notification.service.intake.NotificationIntakeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * Phần chung của các listener: đọc envelope, giao intake, ghi log theo quy ước consumer (topic, partition, offset,
 * group, eventId). Exception được để nổi lên cho error handler của container (retry/DLT), không nuốt ở đây.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventProcessor {

    private final InboundEventReader inboundEventReader;
    private final NotificationTopicRoutes notificationTopicRoutes;
    private final NotificationIntakeService notificationIntakeService;

    /** Topic lạ hoặc event sai hợp đồng ném {@link IllegalArgumentException} (vào DLT, không thử lại). */
    public IntakeResult process(String group, ConsumerRecord<String, String> record) {
        InboundEvent event = inboundEventReader.read(record, notificationTopicRoutes.expectedType(record.topic()));
        IntakeResult result = notificationIntakeService.accept(event);
        log.info("Consumer Notification: topic={}, partition={}, offset={}, group={}, eventId={}, type={}, result={}",
                record.topic(), record.partition(), record.offset(), group, event.eventId(), event.eventType(),
                result);
        return result;
    }
}
