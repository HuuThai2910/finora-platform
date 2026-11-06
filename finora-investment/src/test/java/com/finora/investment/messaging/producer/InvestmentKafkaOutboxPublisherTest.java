package com.finora.investment.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.messaging.FinoraKafkaHeaders;
import com.finora.common.messaging.KafkaEventPublisher;
import com.finora.investment.config.InvestmentKafkaProperties;
import com.finora.investment.service.outbox.InvestmentOutboxMessage;
import com.finora.investment.service.outbox.InvestmentOutboxPublishException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Publisher Investment gửi qua bộ chung của finora-common: record giữ đúng định dạng cũ (key, envelope, header) và lỗi
 * gửi giữ đúng mã, cờ thử lại, chữ cũ mà relay ghi vào {@code last_error_detail}.
 */
class InvestmentKafkaOutboxPublisherTest {

    private static final UUID EVENT_ID = UUID.fromString("8cbcb833-a674-4ed4-94ae-71619d65e0d8");
    private static final Instant OCCURRED_AT = Instant.parse("2026-11-04T03:00:00Z");

    private KafkaOperations<String, String> kafkaOperations;
    private InvestmentKafkaOutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaOperations = mock(KafkaOperations.class);
        InvestmentKafkaProperties properties = new InvestmentKafkaProperties(
                "finora.loan.funding-requested", "finora.investment.loan-fully-funded",
                "finora.investment.loan-funding-expired", "finora.loan.investor-signature-requested",
                "finora.loan.contract-activated", "finora.investment.note-ownership-changed",
                "finora.investment.note-servicing-changed", "finora.loan.reschedule-consent-requested",
                "finora.investment.reschedule-consent-completed", "finora.investment.note-transfer-document-issued",
                Duration.ofMillis(200));
        publisher = new InvestmentKafkaOutboxPublisher(
                new KafkaEventPublisher(kafkaOperations, new ObjectMapper()), properties);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesTheSameEnvelopeKeyAndHeadersAsBefore() {
        doReturn(CompletableFuture.completedFuture(null)).when(kafkaOperations).send(any(ProducerRecord.class));

        publisher.publish(message("NoteOwnershipChanged", 1, "{\"noteId\":1}"));

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations).send(sent.capture());
        ProducerRecord<String, String> record = sent.getValue();
        assertThat(record.topic()).isEqualTo("finora.investment.note-ownership-changed");
        assertThat(record.key()).isEqualTo("NOTE-1");
        assertThat(record.value()).isEqualTo("{\"eventId\":\"" + EVENT_ID
                + "\",\"occurredAt\":\"2026-11-04T03:00:00Z\",\"version\":1,\"data\":{\"noteId\":1}}");
        assertThat(header(record, FinoraKafkaHeaders.EVENT_ID)).isEqualTo(EVENT_ID.toString());
        assertThat(header(record, FinoraKafkaHeaders.EVENT_TYPE)).isEqualTo("NoteOwnershipChanged");
        assertThat(header(record, FinoraKafkaHeaders.EVENT_VERSION)).isEqualTo("1");
        assertThat(header(record, FinoraKafkaHeaders.TRACE_ID)).isEqualTo("trace-1");
    }

    @Test
    void eventWithoutApprovedRouteIsNeverSent() {
        assertFailure(() -> publisher.publish(message("LoanFullyFunded", 1, "{}")),
                "KAFKA_EVENT_ROUTE_MISSING", "Event/version chưa có Kafka route được duyệt", false);
        verify(kafkaOperations, never()).send(any(ProducerRecord.class));
    }

    @Test
    void unreadablePayloadKeepsInvestmentsOwnMessage() {
        assertFailure(() -> publisher.publish(message("NoteOwnershipChanged", 1, "{khong-phai-json")),
                "OUTBOX_PAYLOAD_INVALID", "Không thể tạo Kafka envelope", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ackTimeoutKeepsInvestmentsOwnMessage() {
        doReturn(new CompletableFuture<>()).when(kafkaOperations).send(any(ProducerRecord.class));

        assertFailure(() -> publisher.publish(message("LoanFundingExpired", 1, "{}")),
                "KAFKA_ACK_TIMEOUT", "Kafka chưa xác nhận trong thời gian cho phép", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectedEventKeepsInvestmentsOwnMessage() {
        doThrow(new SerializationException("không serialize được")).when(kafkaOperations).send(any(ProducerRecord.class));

        assertFailure(() -> publisher.publish(message("LoanFullyFunded", 2, "{}")),
                "KAFKA_PUBLISH_REJECTED", "Kafka từ chối event do cấu hình/dữ liệu", false);
    }

    private static InvestmentOutboxMessage message(String eventType, int eventVersion, String payloadJson) {
        return new InvestmentOutboxMessage(EVENT_ID, "Note", "NOTE-1", eventType, eventVersion, payloadJson, "trace-1",
                OCCURRED_AT);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static void assertFailure(Runnable publish, String code, String message, boolean retryable) {
        assertThatThrownBy(publish::run).isInstanceOfSatisfying(InvestmentOutboxPublishException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(code);
            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.isRetryable()).isEqualTo(retryable);
        });
    }
}
