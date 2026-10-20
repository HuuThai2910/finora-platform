package com.finora.loan.messaging.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.finora.loan.domain.restructure.LoanRescheduleType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Dạng JSON của các field bổ sung P1–P3 (W3F) đúng như outbox Loan ghi ra: cùng tên/kiểu mà record của Notification đọc,
 * version giữ v1, field thiếu thì vắng mặt, và không có số thực (Blockchain băm {@code data} theo canonical JSON v2, chỉ
 * nhận số nguyên; tiền và ngày phải là chuỗi).
 */
class ProducerFieldAdditionsContractTest {

    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");
    // Outbox Loan dùng ObjectMapper của Spring Boot (HashingService.toJson). Mặc định của Boot: ngày ISO dạng chuỗi
    // (tắt WRITE_DATES_AS_TIMESTAMPS), bỏ qua field lạ khi đọc, null được ghi ra.
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                    SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .build();

    @Test
    void borrowerIdIsALogicalUserIdStringOnEveryBorrowerFacingLoanEvent() {
        List<JsonNode> payloads = List.of(
                json(new BorrowerSignatureRequestedEventData(10L, "LA-10", "LC-10", "a".repeat(64), "b".repeat(64), 2,
                        NOW, NOW.plusSeconds(86_400), "42")),
                json(new LoanContractActivatedEventData(10L, "LA-10", "LC-10", 20L, "a".repeat(64), "c".repeat(64), 1L,
                        "d".repeat(64), NOW, "42")),
                json(disbursed(LocalDate.of(2026, 11, 17), "8701681.00")),
                json(new LoanFundingCancelledEventData(10L, "LA-10", 20L, 1, "LC-10", 1L, "d".repeat(64),
                        "CONTRACT_EXPIRED", NOW, "42")),
                json(new LoanRescheduleConsentRequestedEventData(UUID.randomUUID(), 10L, "LN-10", "TERM_EXTENSION",
                        LocalDate.of(2026, 11, 17), null, 2, LocalDate.of(2027, 4, 17), NOW, "42")));

        for (JsonNode payload : payloads) {
            assertThat(payload.path("borrowerId").isTextual()).as(payload.toString()).isTrue();
            assertThat(payload.path("borrowerId").asText()).isEqualTo("42");
        }
    }

    @Test
    void nextDueIsAnIsoDateAndATwoDecimalStringOrAbsent() {
        JsonNode withSchedule = json(disbursed(LocalDate.of(2026, 11, 17), "8701681.00"));
        JsonNode rescheduled = json(rescheduled(LocalDate.of(2026, 12, 17), "6123456.00"));

        assertThat(withSchedule.path("nextDueDate").isTextual()).isTrue();
        assertThat(withSchedule.path("nextDueDate").asText()).isEqualTo("2026-11-17");
        assertThat(withSchedule.path("nextDueAmount").asText()).isEqualTo("8701681.00");
        assertThat(rescheduled.path("nextDueDate").asText()).isEqualTo("2026-12-17");
        assertThat(rescheduled.path("nextDueAmount").asText()).isEqualTo("6123456.00");
        // Core không trả lịch: field vắng mặt (không phải chuỗi rỗng hay số 0) để consumer hiểu là "không biết".
        JsonNode withoutSchedule = json(disbursed(null, null));
        JsonNode rescheduledWithoutSchedule = json(rescheduled(null, null));
        assertThat(withoutSchedule.has("nextDueDate")).isFalse();
        assertThat(withoutSchedule.has("nextDueAmount")).isFalse();
        assertThat(rescheduledWithoutSchedule.has("nextDueDate")).isFalse();
        assertThat(rescheduledWithoutSchedule.has("nextDueAmount")).isFalse();
    }

    @Test
    void eventsAnchoredOnBlockchainCarryNoFloatingPointNumbers() {
        for (JsonNode payload : List.of(
                json(new LoanContractActivatedEventData(10L, "LA-10", "LC-10", 20L, "a".repeat(64), "c".repeat(64),
                        1L, "d".repeat(64), NOW, "42")),
                json(disbursed(LocalDate.of(2026, 11, 17), "8701681.00")))) {
            assertThat(nonIntegralNumbers(payload)).as(payload.toString()).isEmpty();
        }
    }

    @Test
    void consumersThatDoNotKnowTheNewFieldsStillReadTheEvent() throws Exception {
        // Investment/Payment/Loan đọc bằng ObjectMapper của Spring Boot (FAIL_ON_UNKNOWN_PROPERTIES tắt) vào record riêng.
        JsonNode payload = json(disbursed(LocalDate.of(2026, 11, 17), "8701681.00"));

        LegacyLoanDisbursed legacy = mapper.treeToValue(payload, LegacyLoanDisbursed.class);

        assertThat(legacy.sagaId()).isNotNull();
        assertThat(legacy.amount()).isEqualTo("10000000.00");
        assertThat(legacy.maturityDate()).isEqualTo(LocalDate.of(2027, 4, 17));
    }

    /** Hình dạng consumer trước P2/P3 (ví dụ Payment chỉ khai báo các field nó dùng). */
    record LegacyLoanDisbursed(UUID sagaId, Long loanApplicationId, String amount, LocalDate maturityDate) {}

    private LoanDisbursedEventData disbursed(LocalDate nextDueDate, String nextDueAmount) {
        return new LoanDisbursedEventData(UUID.randomUUID(), 10L, "LA-10", "LC-10", 20L, "10000000.00", "VND",
                "MOCK-10", 9001L, "FINORA-FINERACT-V4", NOW, "AGRIBANK_REFERENCE_FINORA_2026_V2", "LN-10",
                LocalDate.of(2027, 4, 17), "42", nextDueDate, nextDueAmount);
    }

    private LoanRescheduledEventData rescheduled(LocalDate nextDueDate, String nextDueAmount) {
        return new LoanRescheduledEventData(10L, "LN-10", "42", 9001L, UUID.randomUUID(),
                LoanRescheduleType.TERM_EXTENSION, LocalDate.of(2027, 4, 17), LocalDate.of(2027, 6, 17),
                LocalDate.of(2026, 11, 17), null, 2, NOW, nextDueDate, nextDueAmount);
    }

    private JsonNode json(Object value) {
        try {
            return mapper.readTree(mapper.writeValueAsString(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<String> nonIntegralNumbers(JsonNode node) {
        List<String> found = new ArrayList<>();
        collect(node, "$", found);
        return found;
    }

    private static void collect(JsonNode node, String path, List<String> found) {
        if (node.isNumber() && !node.isIntegralNumber()) found.add(path + "=" + node);
        if (node.isObject()) node.fields().forEachRemaining(entry -> collect(entry.getValue(), path + "." + entry.getKey(), found));
        if (node.isArray()) for (int index = 0; index < node.size(); index++) collect(node.get(index), path + "[" + index + "]", found);
    }
}
