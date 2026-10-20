package com.finora.notification.messaging.consumer;

import com.finora.notification.service.intake.InboundEvent;
import com.finora.notification.service.intake.IntakeResult;
import com.finora.notification.service.intake.NotificationIntakeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Phần chung của các listener: đọc envelope, giao intake, ghi log theo quy ước consumer (topic, partition, offset,
 * group, eventId). Exception được để nổi lên cho error handler của container (retry/DLT), không nuốt ở đây.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventProcessor {

    private final InboundEventReader reader;
    private final NotificationTopicRoutes routes;
    private final NotificationIntakeService intake;

    public IntakeResult process(String group, String topic, int partition, long offset, String payload,
            String headerEventId, String headerEventType, String headerEventVersion) {
        InboundEvent event = reader.read(topic, routes.expectedType(topic), payload, headerEventId, headerEventType,
                headerEventVersion);
        IntakeResult result = intake.accept(event);
        log.info("Consumer Notification: topic={}, partition={}, offset={}, group={}, eventId={}, type={}, result={}",
                topic, partition, offset, group, event.eventId(), event.eventType(), result);
        return result;
    }
}
