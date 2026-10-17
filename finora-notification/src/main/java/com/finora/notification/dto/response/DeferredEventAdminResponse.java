package com.finora.notification.dto.response;

import com.finora.notification.domain.DeferredEvent;
import com.finora.notification.domain.DeferredEventStatus;
import java.time.Instant;
import java.util.UUID;

/** Event chờ người nhận cho màn vận hành; không trả payload. */
public record DeferredEventAdminResponse(Long id, UUID eventId, String eventType, int eventVersion,
        String sourceTopic, DeferredEventStatus status, String waitReason, int attemptCount, Instant nextAttemptAt,
        Instant expiresAt, Instant createdAt) {
    public static DeferredEventAdminResponse from(DeferredEvent value) {
        return new DeferredEventAdminResponse(value.getId(), value.getEventId(), value.getEventType(),
                value.getEventVersion(), value.getSourceTopic(), value.getStatus(), value.getWaitReason(),
                value.getAttemptCount(), value.getNextAttemptAt(), value.getExpiresAt(), value.getCreatedAt());
    }
}
