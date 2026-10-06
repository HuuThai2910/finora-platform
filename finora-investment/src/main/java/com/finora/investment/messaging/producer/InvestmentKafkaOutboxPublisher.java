package com.finora.investment.messaging.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.config.InvestmentKafkaProperties;
import com.finora.investment.service.outbox.InvestmentOutboxMessage;
import com.finora.investment.service.outbox.InvestmentOutboxPublishException;
import com.finora.investment.service.outbox.InvestmentOutboxPublisher;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
import org.springframework.kafka.core.KafkaTemplate;

/** Publisher allowlist cho các event mà Investment sở hữu. */
public class InvestmentKafkaOutboxPublisher implements InvestmentOutboxPublisher {

    static final String EVENT_ID_HEADER = "finora-event-id";
    static final String EVENT_TYPE_HEADER = "finora-event-type";
    static final String EVENT_VERSION_HEADER = "finora-event-version";
    static final String TRACE_ID_HEADER = "finora-trace-id";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final InvestmentKafkaProperties properties;

    public InvestmentKafkaOutboxPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            InvestmentKafkaProperties properties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(InvestmentOutboxMessage message) {
        boolean fullyFunded = "LoanFullyFunded".equals(message.eventType()) && message.eventVersion() == 2;
        boolean ownershipChanged = "NoteOwnershipChanged".equals(message.eventType()) && message.eventVersion() == 1;
        boolean servicingChanged = "InvestorNoteServicingChanged".equals(message.eventType())
                && message.eventVersion() == 1;
        if (!fullyFunded && !ownershipChanged && !servicingChanged) {
            throw failure("KAFKA_EVENT_ROUTE_MISSING",
                    "Event/version chưa có Kafka route được duyệt", false, null);
        }
        ProducerRecord<String, String> record = record(message);
        try {
            kafkaTemplate.send(record).get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("KAFKA_PUBLISH_INTERRUPTED", "Luồng publish Kafka bị ngắt", true, exception);
        } catch (TimeoutException exception) {
            throw failure("KAFKA_ACK_TIMEOUT", "Kafka chưa xác nhận trong thời gian cho phép", true, exception);
        } catch (ExecutionException exception) {
            throw classify(exception.getCause() == null ? exception : exception.getCause());
        } catch (RuntimeException exception) {
            throw classify(exception);
        }
    }

    private ProducerRecord<String, String> record(InvestmentOutboxMessage message) {
        try {
            JsonNode data = objectMapper.readTree(message.payloadJson());
            if (data == null || !data.isObject()) {
                throw failure("OUTBOX_PAYLOAD_INVALID", "Outbox payload phải là JSON object", false, null);
            }
            String value = objectMapper.writeValueAsString(new KafkaEnvelope(
                    message.eventId(), message.occurredAt().toString(), message.eventVersion(), data));
            String topic = switch (message.eventType()) {
                case "NoteOwnershipChanged" -> properties.noteOwnershipChangedTopic();
                case "InvestorNoteServicingChanged" -> properties.noteServicingChangedTopic();
                default -> properties.fullyFundedTopic();
            };
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, message.aggregateId(), value);
            header(record, EVENT_ID_HEADER, message.eventId().toString());
            header(record, EVENT_TYPE_HEADER, message.eventType());
            header(record, EVENT_VERSION_HEADER, Integer.toString(message.eventVersion()));
            header(record, TRACE_ID_HEADER, message.traceId());
            return record;
        } catch (JsonProcessingException exception) {
            throw failure("OUTBOX_PAYLOAD_INVALID", "Không thể tạo Kafka envelope", false, exception);
        }
    }

    private void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private InvestmentOutboxPublishException classify(Throwable cause) {
        if (cause instanceof SerializationException
                || cause instanceof AuthenticationException
                || cause instanceof AuthorizationException
                || cause instanceof InvalidTopicException
                || cause instanceof RecordTooLargeException) {
            return failure("KAFKA_PUBLISH_REJECTED", "Kafka từ chối event do cấu hình/dữ liệu", false, cause);
        }
        if (cause instanceof RetriableException) {
            return failure("KAFKA_TEMPORARILY_UNAVAILABLE", "Kafka tạm thời chưa nhận event", true, cause);
        }
        return failure("KAFKA_PUBLISH_FAILED", "Không publish được event sang Kafka", true, cause);
    }

    private InvestmentOutboxPublishException failure(
            String code, String message, boolean retryable, Throwable cause
    ) {
        return new InvestmentOutboxPublishException(code, message, retryable, cause);
    }

    private record KafkaEnvelope(UUID eventId, String occurredAt, int version, JsonNode data) {
    }
}
