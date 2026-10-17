package com.finora.notification.service.intake;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Một event đã qua kiểm tra envelope/header, sẵn sàng cho handler.
 *
 * @param occurredAt thời điểm producer ghi event (envelope), dùng làm thời điểm của thông báo và để chặn push lỗi thời
 * @param topic      topic nguồn, hoặc topic gốc khi event được worker lấy lại từ bảng chờ
 */
public record InboundEvent(UUID eventId, String eventType, int version, Instant occurredAt, String topic,
        JsonNode data) {

    public EventKey key() {
        return new EventKey(eventType, version);
    }
}
