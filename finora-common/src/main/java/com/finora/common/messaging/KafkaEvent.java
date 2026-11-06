package com.finora.common.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Event đã qua kiểm tra của {@link KafkaEventReader}, sẵn sàng giao cho handler của service.
 *
 * @param eventId    UUID của event; chỉ {@code null} khi hợp đồng không bắt buộc {@code eventId}
 * @param occurredAt thời điểm event; chỉ {@code null} khi hợp đồng cho thiếu hoặc bỏ qua {@code occurredAt}
 * @param version    version của {@code data} trong envelope
 * @param eventType  loại event theo hợp đồng ({@link KafkaEventContract#eventType()})
 * @param traceId    header {@link FinoraKafkaHeaders#TRACE_ID} nếu có
 * @param data       payload đã đổi sang kiểu của service, hoặc {@code JsonNode} gốc khi đọc bằng
 *                   {@link KafkaEventReader#readRaw}
 * @param envelope   envelope đúng như đã đọc, dùng khi cần chuyển tiếp nguyên văn (relay CIC của Loan)
 * @param <T>        kiểu payload
 */
public record KafkaEvent<T>(
        UUID eventId,
        Instant occurredAt,
        int version,
        String eventType,
        String traceId,
        T data,
        KafkaEventEnvelope envelope
) {
}
