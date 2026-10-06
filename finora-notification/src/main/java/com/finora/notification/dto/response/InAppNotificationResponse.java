package com.finora.notification.dto.response;

import com.finora.notification.domain.InAppNotification;
import java.time.Instant;
import java.util.UUID;

public record InAppNotificationResponse(UUID id, String type, String title, String message,
        String businessReference, boolean externalPushRequired, boolean unread,
        Instant occurredAt) {
    public static InAppNotificationResponse from(InAppNotification value) {
        return new InAppNotificationResponse(value.getId(), value.getType(), value.getTitle(),
                value.getMessage(), value.getBusinessReference(), value.isExternalPushRequired(),
                value.getReadAt() == null, value.getOccurredAt());
    }
}
