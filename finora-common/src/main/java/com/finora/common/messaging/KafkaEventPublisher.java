package com.finora.common.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.kafka.core.KafkaOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Cách gửi event duy nhất của FINORA: dựng {@link ProducerRecord} (key = aggregateId, value = {@link KafkaEventEnvelope}
 * dạng JSON, header {@link FinoraKafkaHeaders}), gửi rồi chờ broker xác nhận trong thời gian cho phép; lỗi thì ném
 * {@link KafkaPublishException} đã phân loại để outbox relay quyết định thử lại hay đưa vào dead-letter.
 * <p>
 * Broker vẫn có thể nhận trùng nếu process dừng sau ack mà trước khi relay đánh dấu PUBLISHED, nên consumer phải
 * idempotent theo {@code eventId}. Class không log: service gọi tự log theo quy ước của mình.
 * <p>
 * Không phải Spring bean: finora-user và gateway quét {@code com.finora.common} nhưng không có spring-kafka, nên service
 * nào gửi Kafka thì tự khai {@code @Bean} trong cấu hình Kafka của mình.
 */
public class KafkaEventPublisher {

    private final KafkaOperations<String, String> kafkaOperations;
    private final ObjectMapper objectMapper;

    public KafkaEventPublisher(KafkaOperations<String, String> kafkaOperations, ObjectMapper objectMapper) {
        this.kafkaOperations = Objects.requireNonNull(kafkaOperations, "kafkaOperations");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Gửi event và chờ broker xác nhận. Payload được kiểm và dựng thành record trước khi gọi broker, nên payload hỏng
     * không bao giờ tới Kafka.
     * <p>
     * Phân loại lỗi giữ đúng bản cũ của Loan/Investment, kể cả chỗ không bóc lớp: lỗi bất đồng bộ của
     * {@code KafkaTemplate} tới dưới dạng {@code KafkaProducerException}, lỗi đồng bộ đã qua {@code FutureFailure} tới
     * dưới dạng {@code KafkaException} của Spring, nên cả hai rơi vào {@link KafkaPublishFailure#FAILED} (thử lại được);
     * chỉ exception Kafka ném thẳng (như {@link SerializationException}) mới thành {@link KafkaPublishFailure#REJECTED}
     * hoặc {@link KafkaPublishFailure#TEMPORARILY_UNAVAILABLE}.
     *
     * @param event      event cần gửi; topic đã được service chọn
     * @param ackTimeout thời gian tối đa chờ broker xác nhận
     * @throws KafkaPublishException payload không hợp lệ, broker từ chối, quá hạn chờ, bị ngắt hoặc lỗi khác
     */
    public void publish(KafkaOutgoingEvent event, Duration ackTimeout) {
        Objects.requireNonNull(ackTimeout, "ackTimeout");
        ProducerRecord<String, String> record = toProducerRecord(event);
        try {
            kafkaOperations.send(record).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException(KafkaPublishFailure.INTERRUPTED, exception);
        } catch (TimeoutException exception) {
            throw new KafkaPublishException(KafkaPublishFailure.ACK_TIMEOUT, exception);
        } catch (ExecutionException exception) {
            throw classify(exception.getCause() == null ? exception : exception.getCause());
        } catch (RuntimeException exception) {
            throw classify(exception);
        }
    }

    /**
     * Dựng record sẽ gửi: value là envelope {@code {eventId, occurredAt, version, data}}, header id/type/version luôn có,
     * header trace chỉ có khi {@link KafkaOutgoingEvent#traceId()} khác {@code null}.
     *
     * @throws KafkaPublishException payload không phải JSON hợp lệ hoặc không phải JSON object (không thử lại được)
     */
    public ProducerRecord<String, String> toProducerRecord(KafkaOutgoingEvent event) {
        Objects.requireNonNull(event, "event");
        String value;
        try {
            JsonNode data = objectMapper.readTree(event.payloadJson());
            if (data == null || !data.isObject()) {
                throw new KafkaPublishException(KafkaPublishFailure.PAYLOAD_NOT_OBJECT, null);
            }
            value = objectMapper.writeValueAsString(new KafkaEventEnvelope(
                    event.eventId(), event.occurredAt().toString(), event.eventVersion(), data));
        } catch (JsonProcessingException exception) {
            throw new KafkaPublishException(KafkaPublishFailure.PAYLOAD_UNREADABLE, exception);
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), value);
        addHeader(record, FinoraKafkaHeaders.EVENT_ID, event.eventId().toString());
        addHeader(record, FinoraKafkaHeaders.EVENT_TYPE, event.eventType());
        addHeader(record, FinoraKafkaHeaders.EVENT_VERSION, Integer.toString(event.eventVersion()));
        if (event.traceId() != null) {
            addHeader(record, FinoraKafkaHeaders.TRACE_ID, event.traceId());
        }
        return record;
    }

    private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static KafkaPublishException classify(Throwable failure) {
        if (failure instanceof SerializationException
                || failure instanceof AuthenticationException
                || failure instanceof AuthorizationException
                || failure instanceof InvalidTopicException
                || failure instanceof RecordTooLargeException) {
            return new KafkaPublishException(KafkaPublishFailure.REJECTED, failure);
        }
        if (failure instanceof RetriableException) {
            return new KafkaPublishException(KafkaPublishFailure.TEMPORARILY_UNAVAILABLE, failure);
        }
        return new KafkaPublishException(KafkaPublishFailure.FAILED, failure);
    }
}
