package com.finora.loan.service.outbox;

import java.time.Instant;
import java.util.UUID;

/** Message nội bộ ổn định để transport tạo envelope mà không phụ thuộc JPA entity. */
public record OutboxMessage(
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
