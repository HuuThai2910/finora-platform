package com.finora.notification.messaging.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract.DataRule;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.notification.service.intake.InboundEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * Kiểm envelope {@code {eventId, occurredAt, version, data}} và header {@code finora-event-*} trước khi giao cho intake.
 * Mọi sai lệch là lỗi contract ({@link IllegalArgumentException}, vào DLT); message lỗi không chứa payload.
 * <p>
 * Kiểm bằng {@link KafkaEventReader} dùng chung với hợp đồng {@code anyVersion} + {@code data} là object: mọi version từ
 * 1, header (nếu có, đã bỏ khoảng trắng hai đầu) khớp envelope và loại event của topic, {@code occurredAt} bắt buộc và
 * đọc được bằng {@code Instant} hoặc {@code OffsetDateTime}.
 */
@Component
@RequiredArgsConstructor
public class InboundEventReader {

    private final KafkaEventReader kafkaEventReader;

    /** Đọc record của topic chỉ mang loại event {@code expectedType} (theo {@link NotificationTopicRoutes}). */
    public InboundEvent read(ConsumerRecord<String, String> record, String expectedType) {
        KafkaEvent<JsonNode> event = kafkaEventReader.readRaw(record,
                KafkaEventContract.anyVersion(expectedType).withDataRule(DataRule.OBJECT));
        return new InboundEvent(event.eventId(), event.eventType(), event.version(), event.occurredAt(),
                record.topic(), event.data());
    }
}
