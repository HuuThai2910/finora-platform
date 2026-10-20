package com.finora.notification.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.notification.messaging.event.KafkaEnvelope;
import com.finora.notification.service.intake.InboundEvent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Kiểm envelope {@code {eventId, occurredAt, version, data}} và header {@code finora-event-*} trước khi giao cho intake.
 * Mọi sai lệch là lỗi contract ({@link IllegalArgumentException}, vào DLT); message lỗi không chứa payload.
 */
@Component
@RequiredArgsConstructor
public class InboundEventReader {

    private final ObjectMapper mapper;

    public InboundEvent read(String topic, String expectedType, String payload, String headerEventId,
            String headerEventType, String headerEventVersion) {
        KafkaEnvelope envelope;
        try {
            envelope = mapper.readValue(payload, KafkaEnvelope.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Envelope JSON không hợp lệ trên topic " + topic);
        }
        if (envelope == null || envelope.eventId() == null || envelope.occurredAt() == null
                || envelope.occurredAt().isBlank() || envelope.data() == null || !envelope.data().isObject()
                || envelope.version() < 1) {
            throw new IllegalArgumentException("Envelope thiếu eventId/occurredAt/version/data trên topic " + topic);
        }
        if (headerEventId != null && !UUID.fromString(headerEventId.trim()).equals(envelope.eventId())) {
            throw new IllegalArgumentException("Header finora-event-id khác eventId của envelope");
        }
        if (headerEventType != null && !headerEventType.trim().equals(expectedType)) {
            throw new IllegalArgumentException("Event " + headerEventType + " không thuộc topic " + topic);
        }
        if (headerEventVersion != null && Integer.parseInt(headerEventVersion.trim()) != envelope.version()) {
            throw new IllegalArgumentException("Header finora-event-version khác version của envelope");
        }
        return new InboundEvent(envelope.eventId(), expectedType, envelope.version(),
                instant(envelope.occurredAt().trim()), topic, envelope.data());
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("occurredAt không phải ISO-8601");
            }
        }
    }
}
