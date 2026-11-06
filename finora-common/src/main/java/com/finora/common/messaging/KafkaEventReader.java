package com.finora.common.messaging;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.messaging.KafkaEventContract.DataRule;
import com.finora.common.messaging.KafkaEventContract.HeaderRule;
import com.finora.common.messaging.KafkaEventContract.OccurredAtRule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Cách nhận event duy nhất của FINORA: đọc {@link ConsumerRecord} (value là envelope JSON, header
 * {@link FinoraKafkaHeaders}), kiểm theo {@link KafkaEventContract} rồi trả {@link KafkaEvent} với {@code data} đã đổi
 * sang record của service bằng {@code ObjectMapper#treeToValue}. Listener chỉ còn nhận record, đọc, gọi handler.
 * <p>
 * Event sai hợp đồng (JSON hỏng, thiếu field, version không nhận, header lệch envelope, {@code data} không đổi được
 * kiểu) ném đúng {@link IllegalArgumentException}, không dùng lớp con: error handler Kafka của mọi service FINORA đánh
 * dấu loại này không thử lại nên record vào DLT ngay, và header {@code kafka_dlt-exception-cause-fqcn} của record DLT
 * vẫn là {@code java.lang.IllegalArgumentException} như trước. Message không chứa payload và không gắn exception của
 * Jackson làm cause (message của Jackson có trích đoạn JSON gốc), chỉ ghi loại lỗi và vị trí.
 * <p>
 * Dùng ObjectMapper của service: Spring Boot tắt {@code FAIL_ON_UNKNOWN_PROPERTIES} nên field producer thêm sau không
 * làm hỏng consumer. Không phải Spring bean; service tự khai {@code @Bean}.
 */
public class KafkaEventReader {

    private final ObjectMapper objectMapper;

    public KafkaEventReader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Đọc và kiểm event rồi đổi {@code data} sang {@code dataType}.
     *
     * @throws IllegalArgumentException event sai hợp đồng hoặc {@code data} không đúng kiểu
     */
    public <T> KafkaEvent<T> read(ConsumerRecord<String, String> record, KafkaEventContract contract,
            Class<T> dataType) {
        Objects.requireNonNull(dataType, "dataType");
        KafkaEvent<JsonNode> event = readRaw(record, contract);
        T data;
        try {
            data = objectMapper.treeToValue(event.data(), dataType);
        } catch (JsonProcessingException exception) {
            throw invalid(contract, record, "data không đúng JSON contract (" + describe(exception) + ")");
        }
        return new KafkaEvent<>(event.eventId(), event.occurredAt(), event.version(), event.eventType(),
                event.traceId(), data, event.envelope());
    }

    /**
     * Đọc và kiểm event nhưng giữ {@code data} là {@code JsonNode} gốc (cùng instance với {@code envelope().data()}),
     * cho consumer chung không có record riêng cho từng loại event (Notification, Blockchain băm {@code data}).
     *
     * @throws IllegalArgumentException event sai hợp đồng
     */
    public KafkaEvent<JsonNode> readRaw(ConsumerRecord<String, String> record, KafkaEventContract contract) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(contract, "contract");
        String eventIdHeader = header(record, FinoraKafkaHeaders.EVENT_ID);
        String eventTypeHeader = header(record, FinoraKafkaHeaders.EVENT_TYPE);
        String eventVersionHeader = header(record, FinoraKafkaHeaders.EVENT_VERSION);
        if (contract.headerRule() == HeaderRule.REQUIRED) {
            requireHeader(eventIdHeader, FinoraKafkaHeaders.EVENT_ID, contract, record);
            requireHeader(eventTypeHeader, FinoraKafkaHeaders.EVENT_TYPE, contract, record);
            requireHeader(eventVersionHeader, FinoraKafkaHeaders.EVENT_VERSION, contract, record);
        }
        if (eventTypeHeader != null && !eventTypeHeader.trim().equals(contract.eventType())) {
            throw invalid(contract, record, "header " + FinoraKafkaHeaders.EVENT_TYPE + " là "
                    + eventTypeHeader.trim() + ", không phải " + contract.eventType());
        }
        KafkaEventEnvelope envelope = parseEnvelope(contract, record);
        if (contract.eventIdRequired() && envelope.eventId() == null) {
            throw invalid(contract, record, "envelope thiếu eventId");
        }
        if (!contract.acceptsVersion(envelope.version())) {
            throw invalid(contract, record, "version " + envelope.version() + " không được nhận");
        }
        requireData(envelope.data(), contract, record);
        Instant occurredAt = occurredAt(envelope.occurredAt(), contract, record);
        if (contract.headerRule() != HeaderRule.TYPE_ONLY) {
            requireMatchingEventId(eventIdHeader, envelope.eventId(), contract, record);
            requireMatchingVersion(eventVersionHeader, envelope.version(), contract, record);
        }
        return new KafkaEvent<>(envelope.eventId(), occurredAt, envelope.version(), contract.eventType(),
                header(record, FinoraKafkaHeaders.TRACE_ID), envelope.data(), envelope);
    }

    private KafkaEventEnvelope parseEnvelope(KafkaEventContract contract, ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            throw invalid(contract, record, "record không có value");
        }
        KafkaEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(record.value(), KafkaEventEnvelope.class);
        } catch (JsonProcessingException exception) {
            throw invalid(contract, record, "envelope JSON không hợp lệ (" + describe(exception) + ")");
        }
        if (envelope == null) {
            throw invalid(contract, record, "envelope là JSON null");
        }
        return envelope;
    }

    private static void requireHeader(String value, String name, KafkaEventContract contract,
            ConsumerRecord<String, String> record) {
        if (value == null) {
            throw invalid(contract, record, "thiếu header " + name);
        }
    }

    private static void requireData(JsonNode data, KafkaEventContract contract, ConsumerRecord<String, String> record) {
        if (contract.dataRule() == DataRule.REQUIRED && (data == null || data.isNull())) {
            throw invalid(contract, record, "envelope thiếu data");
        }
        if (contract.dataRule() == DataRule.OBJECT && (data == null || !data.isObject())) {
            throw invalid(contract, record, "data phải là JSON object");
        }
    }

    private static Instant occurredAt(String value, KafkaEventContract contract,
            ConsumerRecord<String, String> record) {
        if (contract.occurredAtRule() == OccurredAtRule.IGNORED) {
            return null;
        }
        if (value == null) {
            if (contract.occurredAtRule() == OccurredAtRule.OPTIONAL) {
                return null;
            }
            throw invalid(contract, record, "envelope thiếu occurredAt");
        }
        String text = value.trim();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException exception) {
                throw invalid(contract, record, "occurredAt không phải ISO-8601");
            }
        }
    }

    private static void requireMatchingEventId(String header, UUID eventId, KafkaEventContract contract,
            ConsumerRecord<String, String> record) {
        if (header == null) {
            return;
        }
        UUID headerEventId;
        try {
            headerEventId = UUID.fromString(header.trim());
        } catch (IllegalArgumentException exception) {
            throw invalid(contract, record, "header " + FinoraKafkaHeaders.EVENT_ID + " không phải UUID");
        }
        if (!headerEventId.equals(eventId)) {
            throw invalid(contract, record, "header " + FinoraKafkaHeaders.EVENT_ID + " khác eventId của envelope");
        }
    }

    private static void requireMatchingVersion(String header, int version, KafkaEventContract contract,
            ConsumerRecord<String, String> record) {
        if (header == null) {
            return;
        }
        int headerVersion;
        try {
            headerVersion = Integer.parseInt(header.trim());
        } catch (NumberFormatException exception) {
            throw invalid(contract, record, "header " + FinoraKafkaHeaders.EVENT_VERSION + " không phải số nguyên");
        }
        if (headerVersion != version) {
            throw invalid(contract, record, "header " + FinoraKafkaHeaders.EVENT_VERSION
                    + " khác version của envelope");
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException invalid(KafkaEventContract contract,
            ConsumerRecord<String, String> record, String reason) {
        String topic = record.topic() == null ? "" : " trên topic " + record.topic();
        return new IllegalArgumentException(contract.eventType() + topic + ": " + reason);
    }

    /** Loại lỗi của Jackson kèm vị trí (đường dẫn field hoặc dòng/cột), không kèm message vì message có trích JSON. */
    private static String describe(JsonProcessingException exception) {
        StringBuilder description = new StringBuilder(exception.getClass().getSimpleName());
        String path = exception instanceof JsonMappingException mapping ? path(mapping.getPath()) : "";
        JsonLocation location = exception.getLocation();
        if (!path.isEmpty()) {
            description.append(" tại ").append(path);
        } else if (location != null && location.getLineNr() > 0) {
            description.append(", dòng ").append(location.getLineNr()).append(", cột ").append(location.getColumnNr());
        }
        return description.toString();
    }

    private static String path(List<JsonMappingException.Reference> references) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : references) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
