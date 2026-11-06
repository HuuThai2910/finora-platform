package com.finora.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Envelope chung phải ra JSON giống từng byte với các bản chép cũ ở Loan, Payment, Investment, Notification. */
class KafkaEventEnvelopeTest {

    private static final UUID EVENT_ID = UUID.fromString("47bcf45e-07ba-42d3-b119-856b6ffd0868");
    private static final String JSON = "{\"eventId\":\"47bcf45e-07ba-42d3-b119-856b6ffd0868\","
            + "\"occurredAt\":\"2026-09-21T00:00:00Z\",\"version\":1,"
            + "\"data\":{\"applicationNumber\":\"LA-001\",\"amount\":\"10000000.00\"}}";

    @Test
    void serializesFieldsInContractOrderLikeTheServiceCopies() throws Exception {
        for (ObjectMapper mapper : new ObjectMapper[] {new ObjectMapper(), Jackson2ObjectMapperBuilder.json().build()}) {
            JsonNode data = mapper.readTree("{\"applicationNumber\":\"LA-001\",\"amount\":\"10000000.00\"}");

            String common = mapper.writeValueAsString(new KafkaEventEnvelope(EVENT_ID, "2026-09-21T00:00:00Z", 1, data));
            String legacy = mapper.writeValueAsString(new LegacyEnvelope(EVENT_ID, "2026-09-21T00:00:00Z", 1, data));

            assertThat(common).isEqualTo(JSON).isEqualTo(legacy);
        }
    }

    @Test
    void readsBackTheSameEnvelope() throws Exception {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

        KafkaEventEnvelope envelope = mapper.readValue(JSON, KafkaEventEnvelope.class);

        assertThat(envelope.eventId()).isEqualTo(EVENT_ID);
        assertThat(envelope.occurredAt()).isEqualTo("2026-09-21T00:00:00Z");
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.data().path("applicationNumber").asText()).isEqualTo("LA-001");
        assertThat(mapper.writeValueAsString(envelope)).isEqualTo(JSON);
    }

    /** Hình dạng của {@code KafkaEventEnvelope}/{@code KafkaEnvelope} đang chép ở các service. */
    private record LegacyEnvelope(UUID eventId, String occurredAt, int version, JsonNode data) {
    }
}
