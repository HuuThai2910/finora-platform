package com.finora.investment.messaging.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Envelope chung của FINORA; payload cụ thể được map sau khi kiểm tra type/version. */
public record KafkaEventEnvelope(
        UUID eventId,
        String occurredAt,
        int version,
        JsonNode data
) {
}
