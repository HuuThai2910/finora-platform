package com.finora.investment.service.outbox;

import java.time.Instant;
import java.util.UUID;

public record InvestmentOutboxMessage(
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        int eventVersion,
        String payloadJson,
        String traceId,
        Instant occurredAt
) {
}
