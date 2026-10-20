package com.finora.notification.dto.response;

import com.finora.notification.domain.InAppNotification;
import com.finora.notification.domain.NotificationCategory;
import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng hộp thư. Giữ nguyên các field cũ (mobile/E2E đang đọc {@code type}, {@code businessReference}); thêm
 * {@code category} để app chọn biểu tượng/màn mở mà không phải đoán theo {@code type}.
 */
public record InAppNotificationResponse(UUID id, String type, NotificationCategory category, String title,
        String message, String businessReference, boolean externalPushRequired, boolean unread,
        Instant occurredAt) {
    public static InAppNotificationResponse from(InAppNotification value) {
        return new InAppNotificationResponse(value.getId(), value.getType(), value.getCategory(), value.getTitle(),
                value.getMessage(), value.getBusinessReference(), value.isExternalPushRequired(),
                value.getReadAt() == null, value.getOccurredAt());
    }
}
