package com.finora.loan.messaging.producer;

import com.finora.common.messaging.KafkaEventPublisher;
import com.finora.common.messaging.KafkaOutgoingEvent;
import com.finora.common.messaging.KafkaPublishException;
import com.finora.loan.config.KafkaPublisherProperties;
import com.finora.loan.service.outbox.OutboxMessage;
import com.finora.loan.service.outbox.OutboxPublishException;
import com.finora.loan.service.outbox.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Kafka adapter chờ broker acknowledgement trước khi relay đánh dấu PUBLISHED.
 * Broker vẫn có thể nhận trùng nếu process dừng sau ack, nên consumer phải idempotent theo eventId.
 * <p>
 * Loan chỉ chọn topic theo allowlist event/version đã duyệt; dựng envelope, header, chờ ack và phân loại lỗi do
 * {@link KafkaEventPublisher} của finora-common làm. Lỗi gửi được đổi sang {@link OutboxPublishException} với đúng mã,
 * thông báo, cờ thử lại và cause để relay ghi {@code last_error_code}/{@code last_error_detail} như trước.
 */
@RequiredArgsConstructor
@Slf4j
public class KafkaOutboxPublisher implements OutboxPublisher {

    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaPublisherProperties kafkaPublisherProperties;

    @Override
    public void publish(OutboxMessage message) {
        String topic = kafkaPublisherProperties.topicFor(message.eventType(), message.eventVersion());
        if (topic == null) {
            throw new OutboxPublishException(
                    "KAFKA_EVENT_ROUTE_MISSING",
                    "Event/version chưa có Kafka route được duyệt",
                    false,
                    null
            );
        }
        try {
            kafkaEventPublisher.publish(new KafkaOutgoingEvent(topic, message.aggregateId(), message.eventId(),
                    message.eventType(), message.eventVersion(), message.occurredAt(), message.payloadJson(),
                    message.traceId()), kafkaPublisherProperties.publishTimeout());
        } catch (KafkaPublishException exception) {
            throw new OutboxPublishException(exception.code(), exception.getMessage(), exception.retryable(),
                    exception.getCause());
        }
        log.info("Đã publish Kafka event: eventId={}, eventType={}, version={}, topic={}",
                message.eventId(), message.eventType(), message.eventVersion(), topic);
    }
}
