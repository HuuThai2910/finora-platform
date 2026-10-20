package com.finora.notification.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.notification.service.intake.InboundEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Envelope/header sai là lỗi contract (IllegalArgumentException → DLT ngay, không thử lại vô ích). */
class InboundEventReaderTest {

    private static final String TOPIC = "finora.loan.disbursed";
    private static final UUID EVENT_ID = UUID.fromString("8cbcb833-a674-4ed4-94ae-71619d65e0d8");
    private final InboundEventReader reader = new InboundEventReader(new ObjectMapper());

    @Test
    void readsEnvelopeAndKeepsTheTopicEventType() {
        InboundEvent event = reader.read(TOPIC, "LoanDisbursed", envelope("2026-10-17T03:00:00Z"),
                EVENT_ID.toString(), "LoanDisbursed", "1");

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.eventType()).isEqualTo("LoanDisbursed");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-17T03:00:00Z"));
        assertThat(event.data().get("loanApplicationId").asLong()).isEqualTo(12L);
    }

    @Test
    void acceptsOffsetTimestampsAndMissingOptionalHeaders() {
        InboundEvent event = reader.read(TOPIC, "LoanDisbursed", envelope("2026-10-17T10:00:00+07:00"), null, null,
                null);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-17T03:00:00Z"));
    }

    @Test
    void rejectsHeaderThatDisagreesWithTheEnvelopeOrTopic() {
        assertThatThrownBy(() -> reader.read(TOPIC, "LoanDisbursed", envelope("2026-10-17T03:00:00Z"),
                UUID.randomUUID().toString(), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(TOPIC, "LoanDisbursed", envelope("2026-10-17T03:00:00Z"),
                null, "LoanSettled", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(TOPIC, "LoanDisbursed", envelope("2026-10-17T03:00:00Z"),
                null, null, "2")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBrokenJsonMissingDataAndBadTimestampWithoutEchoingThePayload() {
        assertThatThrownBy(() -> reader.read(TOPIC, "LoanDisbursed", "{not-json", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("not-json");
        assertThatThrownBy(() -> reader.read(TOPIC, "LoanDisbursed",
                "{\"eventId\":\"" + EVENT_ID + "\",\"occurredAt\":\"2026-10-17T03:00:00Z\",\"version\":1}",
                null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(TOPIC, "LoanDisbursed", envelope("hôm qua"), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String envelope(String occurredAt) {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"occurredAt\":\"" + occurredAt + "\",\"version\":1,"
                + "\"data\":{\"loanApplicationId\":12,\"amount\":\"10000000.00\"}}";
    }
}
