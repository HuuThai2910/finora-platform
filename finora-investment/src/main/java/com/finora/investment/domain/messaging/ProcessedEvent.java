package com.finora.investment.domain.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Dấu idempotency của consumer; không phải bản sao payload Kafka. */
@Entity
@Table(name = "investment_processed_events")
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
        ProcessedEvent event = new ProcessedEvent();
        event.eventId = eventId;
        event.eventType = eventType;
        event.eventVersion = eventVersion;
        event.source = source;
        event.aggregateId = aggregateId;
        event.receivedAt = receivedAt;
        event.processedAt = processedAt;
        return event;
    }
}
