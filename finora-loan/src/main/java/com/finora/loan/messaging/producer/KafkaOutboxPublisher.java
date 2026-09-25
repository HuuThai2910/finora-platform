package com.finora.loan.messaging.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.config.KafkaPublisherProperties;
import com.finora.loan.service.outbox.OutboxMessage;
import com.finora.loan.service.outbox.OutboxPublishException;
import com.finora.loan.service.outbox.OutboxPublisher;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka adapter chờ broker acknowledgement trước khi relay đánh dấu PUBLISHED.
 * Broker vẫn có thể nhận trùng nếu process dừng sau ack, nên consumer phải idempotent theo eventId.
 */
public class KafkaOutboxPublisher implements OutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaOutboxPublisher.class);

    static final String EVENT_ID_HEADER = "finora-event-id";
    static final String EVENT_TYPE_HEADER = "finora-event-type";
    static final String EVENT_VERSION_HEADER = "finora-event-version";
    static final String TRACE_ID_HEADER = "finora-trace-id";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaPublisherProperties properties;

    public KafkaOutboxPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            KafkaPublisherProperties properties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxMessage message) {
        String topic = properties.topicFor(message.eventType(), message.eventVersion());
        if (topic == null) {
            throw failure(
                    "KAFKA_EVENT_ROUTE_MISSING",
                    "Event/version chưa có Kafka route được duyệt",
                    false,
                    null
            );
        }
        ProducerRecord<String, String> record = record(topic, message);
        try {
            kafkaTemplate.send(record).get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            LOGGER.info("Đã publish Kafka event: eventId={}, eventType={}, version={}, topic={}",
                    message.eventId(), message.eventType(), message.eventVersion(), topic);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("KAFKA_PUBLISH_INTERRUPTED", "Luồng publish Kafka bị ngắt", true, exception);
        } catch (TimeoutException exception) {
            throw failure("KAFKA_ACK_TIMEOUT", "Kafka chưa xác nhận event trong thời gian cho phép", true, exception);
        } catch (ExecutionException exception) {
            throw classify(exception.getCause() == null ? exception : exception.getCause());
        } catch (RuntimeException exception) {
            throw classify(exception);
        }
    }

    private ProducerRecord<String, String> record(String topic, OutboxMessage message) {
        try {
            JsonNode data = objectMapper.readTree(message.payloadJson());
            if (data == null || !data.isObject()) {
                throw failure(
                        "OUTBOX_PAYLOAD_INVALID",
                        "Outbox payload phải là JSON object",
                        false,
                        null
                );
            }
            String value = objectMapper.writeValueAsString(new KafkaEventEnvelope(
                    message.eventId(), message.occurredAt().toString(), message.eventVersion(), data));
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, message.aggregateId(), value);
            addHeader(record, EVENT_ID_HEADER, message.eventId().toString());
            addHeader(record, EVENT_TYPE_HEADER, message.eventType());
            addHeader(record, EVENT_VERSION_HEADER, Integer.toString(message.eventVersion()));
            addHeader(record, TRACE_ID_HEADER, message.traceId());
            return record;
        } catch (JsonProcessingException exception) {
            throw failure(
                    "OUTBOX_PAYLOAD_INVALID",
                    "Không thể tạo Kafka envelope từ outbox payload",
                    false,
                    exception
            );
        }
    }

    private void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private OutboxPublishException classify(Throwable failure) {
        if (failure instanceof SerializationException
                || failure instanceof AuthenticationException
                || failure instanceof AuthorizationException
                || failure instanceof InvalidTopicException
                || failure instanceof RecordTooLargeException) {
            return failure("KAFKA_PUBLISH_REJECTED", "Kafka từ chối event do cấu hình hoặc dữ liệu", false, failure);
        }
        if (failure instanceof RetriableException) {
            return failure("KAFKA_TEMPORARILY_UNAVAILABLE", "Kafka tạm thời chưa nhận event", true, failure);
        }
        return failure("KAFKA_PUBLISH_FAILED", "Không publish được event sang Kafka", true, failure);
    }

    private OutboxPublishException failure(
            String code,
            String message,
            boolean retryable,
            Throwable cause
    ) {
        return new OutboxPublishException(code, message, retryable, cause);
    }
}
