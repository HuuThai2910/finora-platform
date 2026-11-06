package com.finora.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.messaging.KafkaEventContract.DataRule;
import com.finora.common.messaging.KafkaEventContract.HeaderRule;
import com.finora.common.messaging.KafkaEventContract.OccurredAtRule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bên nhận: event hợp lệ ra đúng eventId/occurredAt/version/data; event sai hợp đồng ném đúng
 * {@link IllegalArgumentException} (error handler mọi service không retry loại này, header DLT giữ FQCN cũ).
 */
class KafkaEventReaderTest {

    private static final String TOPIC = "finora.loan.disbursed";
    private static final UUID EVENT_ID = UUID.fromString("8cbcb833-a674-4ed4-94ae-71619d65e0d8");
    private static final String DATA = "{\"loanApplicationId\":12,\"amount\":\"10000000.00\"}";
    private static final KafkaEventContract LOAN_DISBURSED = KafkaEventContract.of("LoanDisbursed", 1);

    // Cùng mặc định Spring Boot dùng cho bean ObjectMapper của các service (bỏ qua field lạ).
    private final KafkaEventReader reader = new KafkaEventReader(Jackson2ObjectMapperBuilder.json().build());

    @Test
    void readsTypedEventFromEnvelopeAndHeaders() {
        KafkaEvent<SampleData> event = reader.read(
                record(envelope("2026-10-17T03:00:00Z", DATA), EVENT_ID.toString(), "LoanDisbursed", "1", "trace-1"),
                LOAN_DISBURSED, SampleData.class);

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-17T03:00:00Z"));
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.eventType()).isEqualTo("LoanDisbursed");
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.data()).isEqualTo(new SampleData(12L, "10000000.00"));
        assertThat(event.envelope().eventId()).isEqualTo(EVENT_ID);
        assertThat(event.envelope().occurredAt()).isEqualTo("2026-10-17T03:00:00Z");
    }

    @Test
    void headersAreOptionalAndProducerFieldAdditionsAreIgnored() {
        String data = "{\"loanApplicationId\":12,\"amount\":\"10000000.00\",\"borrowerId\":\"42\"}";

        KafkaEvent<SampleData> event = reader.read(record(envelope("2026-10-17T03:00:00Z", data), null, null, null,
                null), LOAN_DISBURSED, SampleData.class);

        assertThat(event.data().loanApplicationId()).isEqualTo(12L);
        assertThat(event.traceId()).isNull();
    }

    @Test
    void readRawKeepsTheOriginalDataNode() {
        KafkaEvent<JsonNode> event = reader.readRaw(record(envelope("2026-10-17T03:00:00Z", DATA), null, null, null,
                null), LOAN_DISBURSED);

        assertThat(event.data()).isSameAs(event.envelope().data());
        assertThat(event.data().get("loanApplicationId").asLong()).isEqualTo(12L);
    }

    @Test
    void headerThatDisagreesWithTheEnvelopeOrContractIsRejected() {
        String payload = envelope("2026-10-17T03:00:00Z", DATA);

        assertInvalid(record(payload, UUID.randomUUID().toString(), null, null, null), LOAN_DISBURSED,
                "header finora-event-id khác eventId của envelope");
        assertInvalid(record(payload, "not-a-uuid", null, null, null), LOAN_DISBURSED,
                "header finora-event-id không phải UUID");
        assertInvalid(record(payload, null, "LoanSettled", null, null), LOAN_DISBURSED,
                "header finora-event-type là LoanSettled, không phải LoanDisbursed");
        assertInvalid(record(payload, null, null, "2", null), LOAN_DISBURSED,
                "header finora-event-version khác version của envelope");
        assertInvalid(record(payload, null, null, "v1", null), LOAN_DISBURSED,
                "header finora-event-version không phải số nguyên");
    }

    @Test
    void headerValuesAreTrimmedBeforeComparing() {
        KafkaEvent<SampleData> event = reader.read(record(envelope("2026-10-17T03:00:00Z", DATA),
                " " + EVENT_ID + " ", " LoanDisbursed ", " 1 ", null), LOAN_DISBURSED, SampleData.class);

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    void requiredHeadersMustBePresent() {
        KafkaEventContract contract = LOAN_DISBURSED.withHeaderRule(HeaderRule.REQUIRED);
        String payload = envelope("2026-10-17T03:00:00Z", DATA);

        assertInvalid(record(payload, null, "LoanDisbursed", "1", null), contract, "thiếu header finora-event-id");
        assertInvalid(record(payload, EVENT_ID.toString(), null, "1", null), contract,
                "thiếu header finora-event-type");
        assertInvalid(record(payload, EVENT_ID.toString(), "LoanDisbursed", null, null), contract,
                "thiếu header finora-event-version");
        assertThat(reader.read(record(payload, EVENT_ID.toString(), "LoanDisbursed", "1", null), contract,
                SampleData.class).eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    void envelopeMustCarryEventIdOccurredAtDataAndAnAcceptedVersion() {
        assertInvalid(record("{\"occurredAt\":\"2026-10-17T03:00:00Z\",\"version\":1,\"data\":" + DATA + "}",
                null, null, null, null), LOAN_DISBURSED, "envelope thiếu eventId");
        assertInvalid(record("{\"eventId\":\"" + EVENT_ID + "\",\"version\":1,\"data\":" + DATA + "}",
                null, null, null, null), LOAN_DISBURSED, "envelope thiếu occurredAt");
        assertInvalid(record("{\"eventId\":\"" + EVENT_ID + "\",\"occurredAt\":\"2026-10-17T03:00:00Z\",\"version\":1}",
                null, null, null, null), LOAN_DISBURSED, "envelope thiếu data");
        assertInvalid(record(envelope("2026-10-17T03:00:00Z", "null"), null, null, null, null), LOAN_DISBURSED,
                "envelope thiếu data");
        assertInvalid(record(envelope(2, "2026-10-17T03:00:00Z", DATA), null, null, null, null), LOAN_DISBURSED,
                "version 2 không được nhận");
        assertInvalid(record(envelope("hôm qua", DATA), null, null, null, null), LOAN_DISBURSED,
                "occurredAt không phải ISO-8601");
    }

    @Test
    void severalVersionsOrAnyVersionCanBeAccepted() {
        KafkaEventContract twoVersions = KafkaEventContract.of("LoanDisbursed", 1, 2);
        assertThat(reader.readRaw(record(envelope(2, "2026-10-17T03:00:00Z", DATA), null, null, "2", null),
                twoVersions).version()).isEqualTo(2);

        KafkaEventContract anyVersion = KafkaEventContract.anyVersion("LoanDisbursed");
        assertThat(reader.readRaw(record(envelope(3, "2026-10-17T03:00:00Z", DATA), null, null, "3", null),
                anyVersion).version()).isEqualTo(3);
        assertInvalid(record(envelope(3, "2026-10-17T03:00:00Z", DATA), null, null, "1", null), anyVersion,
                "header finora-event-version khác version của envelope");
        assertInvalid(record(envelope(0, "2026-10-17T03:00:00Z", DATA), null, null, null, null), anyVersion,
                "version 0 không được nhận");
    }

    @Test
    void occurredAtAcceptsOffsetTimestampsAndCanBeOptionalOrIgnored() {
        assertThat(reader.readRaw(record(envelope("2026-10-17T10:00+07:00", DATA), null, null, null, null),
                LOAN_DISBURSED).occurredAt()).isEqualTo(Instant.parse("2026-10-17T03:00:00Z"));

        String withoutOccurredAt = "{\"eventId\":\"" + EVENT_ID + "\",\"version\":1,\"data\":" + DATA + "}";
        KafkaEventContract optional = LOAN_DISBURSED.withOccurredAtRule(OccurredAtRule.OPTIONAL);
        assertThat(reader.readRaw(record(withoutOccurredAt, null, null, null, null), optional).occurredAt()).isNull();
        assertInvalid(record(envelope("", DATA), null, null, null, null), optional, "occurredAt không phải ISO-8601");

        KafkaEventContract ignored = LOAN_DISBURSED.withOccurredAtRule(OccurredAtRule.IGNORED);
        assertThat(reader.readRaw(record(envelope("hôm qua", DATA), null, null, null, null), ignored).occurredAt())
                .isNull();
    }

    @Test
    void objectDataRuleRejectsArraysAndScalars() {
        KafkaEventContract contract = KafkaEventContract.anyVersion("LoanDisbursed").withDataRule(DataRule.OBJECT);

        assertInvalid(record(envelope("2026-10-17T03:00:00Z", "[1,2]"), null, null, null, null), contract,
                "data phải là JSON object");
        assertInvalid(record(envelope("2026-10-17T03:00:00Z", "\"x\""), null, null, null, null), contract,
                "data phải là JSON object");
    }

    @Test
    void typeOnlyContractChecksJustTheTypeHeaderLikePaymentServicingConsumers() {
        KafkaEventContract lenient = LOAN_DISBURSED.withHeaderRule(HeaderRule.TYPE_ONLY).withEventIdRequired(false)
                .withOccurredAtRule(OccurredAtRule.IGNORED).withDataRule(DataRule.OPTIONAL);

        KafkaEvent<SampleData> event = reader.read(record("{\"version\":1}", UUID.randomUUID().toString(), null, "9",
                null), lenient, SampleData.class);

        assertThat(event.eventId()).isNull();
        assertThat(event.occurredAt()).isNull();
        assertThat(event.data()).isNull();
        // Header type sai bị chặn trước cả khi đọc JSON.
        assertInvalid(record("{broken", null, "LoanSettled", null, null), lenient,
                "header finora-event-type là LoanSettled, không phải LoanDisbursed");
        assertInvalid(record("{\"version\":2}", null, null, null, null), lenient, "version 2 không được nhận");
    }

    @Test
    void optionalDataThatIsJsonNullReachesTheHandlerAsNull() {
        KafkaEventContract contract = LOAN_DISBURSED.withDataRule(DataRule.OPTIONAL);

        KafkaEvent<SampleData> event = reader.read(record(envelope("2026-10-17T03:00:00Z", "null"), null, null, null,
                null), contract, SampleData.class);

        assertThat(event.data()).isNull();
    }

    @Test
    void envelopeKeepsTheOriginalOccurredAtTextForRelaying() {
        KafkaEvent<JsonNode> event = reader.readRaw(record(envelope("2026-10-17T03:00:00.000Z", DATA), null, null, null,
                null), LOAN_DISBURSED);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-17T03:00:00Z"));
        assertThat(event.envelope().occurredAt()).isEqualTo("2026-10-17T03:00:00.000Z");
    }

    @Test
    void brokenJsonAndWrongDataShapeAreRejectedWithoutEchoingThePayload() {
        assertThatThrownBy(() -> reader.read(record("{not-json", null, null, null, null), LOAN_DISBURSED,
                SampleData.class))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("LoanDisbursed trên topic finora.loan.disbursed: envelope JSON không hợp lệ")
                .hasMessageNotContaining("not-json")
                .hasNoCause();
        assertInvalid(record("null", null, null, null, null), LOAN_DISBURSED, "envelope là JSON null");
        assertInvalid(record(null, null, null, null, null), LOAN_DISBURSED, "record không có value");
        assertThatThrownBy(() -> reader.read(record(envelope("2026-10-17T03:00:00Z",
                "{\"loanApplicationId\":\"secret-value\"}"), null, null, null, null), LOAN_DISBURSED, SampleData.class))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data không đúng JSON contract")
                .hasMessageContaining("loanApplicationId")
                .hasMessageNotContaining("secret-value")
                .hasNoCause();
    }

    @Test
    void strictMapperOfTheServiceStillDecidesUnknownFields() {
        KafkaEventReader strict = new KafkaEventReader(new ObjectMapper());
        String data = "{\"loanApplicationId\":12,\"amount\":\"1.00\",\"borrowerId\":\"42\"}";

        assertThatThrownBy(() -> strict.read(record(envelope("2026-10-17T03:00:00Z", data), null, null, null, null),
                LOAN_DISBURSED, SampleData.class))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UnrecognizedPropertyException tại borrowerId");
    }

    @Test
    void contractRejectsBlankTypeAndNonPositiveVersions() {
        assertThatThrownBy(() -> KafkaEventContract.of(" ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaEventContract.of("LoanDisbursed", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(KafkaEventContract.of("LoanDisbursed", 1).acceptsVersion(2)).isFalse();
        assertThat(KafkaEventContract.anyVersion("LoanDisbursed").acceptsVersion(7)).isTrue();
    }

    private void assertInvalid(ConsumerRecord<String, String> record, KafkaEventContract contract, String reason) {
        assertThatThrownBy(() -> reader.readRaw(record, contract))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(contract.eventType() + " trên topic " + TOPIC + ": " + reason);
    }

    private static ConsumerRecord<String, String> record(String value, String eventId, String eventType,
            String eventVersion, String traceId) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 7L, "LA-12", value);
        addHeader(record, FinoraKafkaHeaders.EVENT_ID, eventId);
        addHeader(record, FinoraKafkaHeaders.EVENT_TYPE, eventType);
        addHeader(record, FinoraKafkaHeaders.EVENT_VERSION, eventVersion);
        addHeader(record, FinoraKafkaHeaders.TRACE_ID, traceId);
        return record;
    }

    private static void addHeader(ConsumerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String envelope(String occurredAt, String data) {
        return envelope(1, occurredAt, data);
    }

    private static String envelope(int version, String occurredAt, String data) {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"occurredAt\":\"" + occurredAt + "\",\"version\":" + version
                + ",\"data\":" + data + "}";
    }

    private record SampleData(Long loanApplicationId, String amount) {
    }
}
