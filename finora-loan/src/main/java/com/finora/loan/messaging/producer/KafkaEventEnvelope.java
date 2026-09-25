package com.finora.loan.messaging.producer;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Envelope Kafka v1 dùng chung; event type nằm ở route/topic và header để không đổi nghĩa payload. */
public record KafkaEventEnvelope(
        UUID eventId,
        String occurredAt,
        int version,
        JsonNode data
) {
}
