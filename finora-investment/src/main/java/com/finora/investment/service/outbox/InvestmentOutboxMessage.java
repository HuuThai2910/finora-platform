package com.finora.investment.service.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Event outbox đọc từ bảng để gửi Kafka: định danh, aggregate, loại và phiên bản event, payload JSON, trace id.
 */
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
