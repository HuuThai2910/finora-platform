package com.finora.loan.domain.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Dấu idempotency của Kafka consumer; payload nghiệp vụ vẫn thuộc aggregate Loan. */
@Entity
@Table(name = "loan_processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false, updatable = false)
    private Integer eventVersion;

    @Column(nullable = false, length = 50, updatable = false)
    private String source;

    @Column(name = "aggregate_id", nullable = false, length = 100, updatable = false)
    private String aggregateId;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    public static ProcessedEvent create(
            UUID eventId,
            String eventType,
            int eventVersion,
            String source,
            String aggregateId,
            Instant receivedAt,
            Instant processedAt
    ) {
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion phải dương");
        }
        ProcessedEvent event = new ProcessedEvent();
        event.eventId = Objects.requireNonNull(eventId, "eventId");
        event.eventType = requireText(eventType, "eventType");
        event.eventVersion = eventVersion;
        event.source = requireText(source, "source");
        event.aggregateId = requireText(aggregateId, "aggregateId");
        event.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        event.processedAt = Objects.requireNonNull(processedAt, "processedAt");
        return event;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        return value.trim();
    }
}
