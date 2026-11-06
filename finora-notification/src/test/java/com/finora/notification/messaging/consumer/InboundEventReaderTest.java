package com.finora.notification.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.messaging.FinoraKafkaHeaders;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.notification.service.intake.InboundEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Envelope/header sai là lỗi contract (IllegalArgumentException → DLT ngay, không thử lại vô ích). */
class InboundEventReaderTest {

    private static final String TOPIC = "finora.loan.disbursed";
    private static final UUID EVENT_ID = UUID.fromString("8cbcb833-a674-4ed4-94ae-71619d65e0d8");
    private final InboundEventReader reader = new InboundEventReader(new KafkaEventReader(new ObjectMapper()));

    @Test
    void readsEnvelopeAndKeepsTheTopicEventType() {
        InboundEvent event = reader.read(record(envelope("2026-10-17T03:00:00Z"), EVENT_ID.toString(),
                "LoanDisbursed", "1"), "LoanDisbursed");

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.eventType()).isEqualTo("LoanDisbursed");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-17T03:00:00Z"));
        assertThat(event.topic()).isEqualTo(TOPIC);
        assertThat(event.data().get("loanApplicationId").asLong()).isEqualTo(12L);
    }

    @Test
    void acceptsOffsetTimestampsAndMissingOptionalHeaders() {
        InboundEvent event = reader.read(record(envelope("2026-10-17T10:00:00+07:00"), null, null, null),
                "LoanDisbursed");

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-17T03:00:00Z"));
    }

    @Test
    void rejectsHeaderThatDisagreesWithTheEnvelopeOrTopic() {
        assertThatThrownBy(() -> reader.read(record(envelope("2026-10-17T03:00:00Z"),
                UUID.randomUUID().toString(), null, null), "LoanDisbursed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(record(envelope("2026-10-17T03:00:00Z"),
                null, "LoanSettled", null), "LoanDisbursed")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(record(envelope("2026-10-17T03:00:00Z"),
                null, null, "2"), "LoanDisbursed")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBrokenJsonMissingDataAndBadTimestampWithoutEchoingThePayload() {
        assertThatThrownBy(() -> reader.read(record("{not-json", null, null, null), "LoanDisbursed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("not-json");
        assertThatThrownBy(() -> reader.read(record(
                "{\"eventId\":\"" + EVENT_ID + "\",\"occurredAt\":\"2026-10-17T03:00:00Z\",\"version\":1}",
                null, null, null), "LoanDisbursed")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(record(envelope("hôm qua"), null, null, null), "LoanDisbursed"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAnyVersionFromOneTrimmedHeadersAndOffsetTimestampsWithoutSeconds() {
        // Notification là consumer chung: không ghim version, header có khoảng trắng hai đầu vẫn khớp envelope.
        InboundEvent event = reader.read(record(envelope("2026-10-17T10:00+07:00", 3), " " + EVENT_ID + " ",
                " LoanDisbursed ", " 3 "), "LoanDisbursed");

        assertThat(event.version()).isEqualTo(3);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-17T03:00:00Z"));
    }

    @Test
    void rejectsNonObjectDataVersionZeroAndBlankTimestampAsPlainIllegalArgumentException() {
        String base = "{\"eventId\":\"" + EVENT_ID + "\",\"occurredAt\":\"2026-10-17T03:00:00Z\",\"version\":1,";

        assertThatThrownBy(() -> reader.read(record(base + "\"data\":[1,2]}", null, null, null), "LoanDisbursed"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(record(base + "\"data\":\"text\"}", null, null, null), "LoanDisbursed"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(record(envelope("2026-10-17T03:00:00Z", 0), null, null, null),
                "LoanDisbursed")).isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(record(envelope("   "), null, null, null), "LoanDisbursed"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    private static String envelope(String occurredAt) {
        return envelope(occurredAt, 1);
    }

    private static String envelope(String occurredAt, int version) {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"occurredAt\":\"" + occurredAt + "\",\"version\":" + version + ","
                + "\"data\":{\"loanApplicationId\":12,\"amount\":\"10000000.00\"}}";
    }

    private static ConsumerRecord<String, String> record(String payload, String id, String type, String version) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 0L, null, payload);
        addHeader(record, FinoraKafkaHeaders.EVENT_ID, id);
        addHeader(record, FinoraKafkaHeaders.EVENT_TYPE, type);
        addHeader(record, FinoraKafkaHeaders.EVENT_VERSION, version);
        return record;
    }

    private static void addHeader(ConsumerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
