package com.finora.common.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Một event cần gửi lên Kafka, thường lấy từ một dòng outbox của service nguồn. Topic do service tự chọn theo allowlist
 * event/version của mình; finora-common không giữ bảng định tuyến.
 *
 * @param topic        topic đích đã được service duyệt
 * @param key          key của record, là aggregateId để giữ thứ tự theo aggregate trong một partition
 * @param eventId      UUID của event, ghi vào envelope và header {@link FinoraKafkaHeaders#EVENT_ID}
 * @param eventType    loại event, ghi vào header {@link FinoraKafkaHeaders#EVENT_TYPE}
 * @param eventVersion version của payload, ghi vào envelope và header {@link FinoraKafkaHeaders#EVENT_VERSION}
 * @param occurredAt   thời điểm event, ghi vào envelope bằng {@link Instant#toString()}
 * @param payloadJson  payload JSON, bắt buộc là JSON object
 * @param traceId      trace id cho header {@link FinoraKafkaHeaders#TRACE_ID}; {@code null} thì không gắn header này
 */
public record KafkaOutgoingEvent(
        String topic,
        String key,
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String payloadJson,
        String traceId
) {
}
