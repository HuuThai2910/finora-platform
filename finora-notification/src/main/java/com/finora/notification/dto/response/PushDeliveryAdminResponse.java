package com.finora.notification.dto.response;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.PushDelivery;
import com.finora.notification.domain.PushDeliveryStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Một lần gửi push cho màn vận hành. Không có token hay nội dung chi tiết; {@code provider = LOG_MOCK} là bản giả lập.
 */
public record PushDeliveryAdminResponse(Long id, UUID notificationId, UUID deviceId, String recipientId,
        NotificationCategory category, PushDeliveryStatus status, String provider, int attemptCount,
        String lastErrorCode, Instant nextAttemptAt, Instant sentAt, Instant completedAt, Instant updatedAt) {
    public static PushDeliveryAdminResponse from(PushDelivery value) {
        return new PushDeliveryAdminResponse(value.getId(), value.getNotificationId(), value.getDeviceId(),
                value.getRecipientId(), value.getCategory(), value.getStatus(), value.getProvider(),
                value.getAttemptCount(), value.getLastErrorCode(), value.getNextAttemptAt(), value.getSentAt(),
                value.getCompletedAt(), value.getUpdatedAt());
    }
}
