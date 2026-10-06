package com.finora.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "in_app_notifications", uniqueConstraints = @UniqueConstraint(
        name = "uq_notification_delivery", columnNames = {"source_event_id", "recipient_id", "type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InAppNotification {
    @Id private UUID id;
    @Column(name = "source_event_id", nullable = false, updatable = false) private UUID sourceEventId;
    @Column(name = "recipient_id", nullable = false, length = 100, updatable = false) private String recipientId;
    @Column(nullable = false, length = 50, updatable = false) private String type;
    @Column(nullable = false, length = 160, updatable = false) private String title;
    @Column(nullable = false, length = 1000, updatable = false) private String message;
    @Column(name = "business_reference", length = 100, updatable = false) private String businessReference;
    @Column(name = "external_push_required", nullable = false, updatable = false) private boolean externalPushRequired;
    @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;
    @Column(name = "read_at") private Instant readAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    public static InAppNotification create(UUID sourceEventId, String recipientId,
            String type, String title, String message, String businessReference,
            boolean externalPushRequired, Instant occurredAt, Instant now) {
        InAppNotification value = new InAppNotification();
        value.id = UUID.randomUUID();
        value.sourceEventId = sourceEventId;
        value.recipientId = recipientId;
        value.type = type;
        value.title = title;
        value.message = message;
        value.businessReference = businessReference;
        value.externalPushRequired = externalPushRequired;
        value.occurredAt = occurredAt;
        value.createdAt = now;
        return value;
    }

    public void markRead(Instant now) {
        if (readAt == null) readAt = now;
    }
}
