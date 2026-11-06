package com.finora.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Envelope v1 của mọi event FINORA trên Kafka: {@code {"eventId", "occurredAt", "version", "data"}} theo đúng thứ tự
 * này (rule 04). Loại event không nằm trong envelope mà ở topic và header {@link FinoraKafkaHeaders#EVENT_TYPE}, để
 * payload không đổi nghĩa khi có thêm loại event.
 * <p>
 * Thay các bản chép {@code KafkaEventEnvelope} ở Loan, Payment, Investment và {@code KafkaEnvelope} ở Notification: cùng
 * tên field, cùng thứ tự, không annotation Jackson, nên JSON sinh ra giống từng byte với bản cũ. {@code occurredAt} giữ
 * nguyên chuỗi producer đã ghi (Loan chuyển tiếp nguyên envelope này sang CIC qua HTTP).
 *
 * @param eventId    UUID của event; consumer chống trùng theo giá trị này
 * @param occurredAt thời điểm event xảy ra, ISO-8601 có offset (producer ghi {@code Instant#toString()})
 * @param version    version của {@code data}
 * @param data       payload nghiệp vụ, là JSON object
 */
public record KafkaEventEnvelope(
        UUID eventId,
        String occurredAt,
        int version,
        JsonNode data
) {
}
