package com.finora.common.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Bên gửi: record/header/key giống publisher cũ của Loan/Investment, lỗi phân loại đúng mã đang ghi vào outbox. */
class KafkaEventPublisherTest {

    private static final String TOPIC = "finora.loan.loan-terms-authorized";
    private static final UUID EVENT_ID = UUID.fromString("47bcf45e-07ba-42d3-b119-856b6ffd0868");
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(1);

    private final ObjectMapper objectMapper = new ObjectMapper();
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final KafkaEventPublisher publisher = new KafkaEventPublisher(template, objectMapper);

    @Test
    void sendsEnvelopeKeyedByAggregateWithAllFinoraHeaders() {
        when(template.send(anyRecord())).thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(event("{\"applicationNumber\":\"LA-001\"}", "trace-001"), ACK_TIMEOUT);

        ProducerRecord<String, String> sent = sentRecord();
        assertThat(sent.topic()).isEqualTo(TOPIC);
        assertThat(sent.partition()).isNull();
        assertThat(sent.key()).isEqualTo("LA-001");
        assertThat(sent.value()).isEqualTo("{\"eventId\":\"47bcf45e-07ba-42d3-b119-856b6ffd0868\","
                + "\"occurredAt\":\"2026-09-21T00:00:00Z\",\"version\":1,\"data\":{\"applicationNumber\":\"LA-001\"}}");
        assertThat(Arrays.stream(sent.headers().toArray()).map(Header::key)).containsExactly(
                "finora-event-id", "finora-event-type", "finora-event-version", "finora-trace-id");
        assertThat(header(sent, FinoraKafkaHeaders.EVENT_ID)).isEqualTo(EVENT_ID.toString());
        assertThat(header(sent, FinoraKafkaHeaders.EVENT_TYPE)).isEqualTo("LoanTermsAuthorized");
        assertThat(header(sent, FinoraKafkaHeaders.EVENT_VERSION)).isEqualTo("1");
        assertThat(header(sent, FinoraKafkaHeaders.TRACE_ID)).isEqualTo("trace-001");
    }

    @Test
    void omitsTraceHeaderWhenTheSourceHasNoTraceIdLikePayment() {
        when(template.send(anyRecord())).thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(event("{\"amount\":\"10000000\"}", null), ACK_TIMEOUT);

        assertThat(Arrays.stream(sentRecord().headers().toArray()).map(Header::key)).containsExactly(
                "finora-event-id", "finora-event-type", "finora-event-version");
    }

    @Test
    void rejectsNonObjectAndBrokenPayloadWithoutCallingTheBroker() {
        assertThatThrownBy(() -> publisher.publish(event("[]", "trace-001"), ACK_TIMEOUT))
                .isInstanceOfSatisfying(KafkaPublishException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(KafkaPublishFailure.PAYLOAD_NOT_OBJECT);
                    assertThat(failure.code()).isEqualTo("OUTBOX_PAYLOAD_INVALID");
                    assertThat(failure.getMessage()).isEqualTo("Outbox payload phải là JSON object");
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getCause()).isNull();
                });
        assertThatThrownBy(() -> publisher.publish(event("{\"amount\":", "trace-001"), ACK_TIMEOUT))
                .isInstanceOfSatisfying(KafkaPublishException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(KafkaPublishFailure.PAYLOAD_UNREADABLE);
                    assertThat(failure.code()).isEqualTo("OUTBOX_PAYLOAD_INVALID");
                    assertThat(failure.getMessage()).isEqualTo("Không thể tạo Kafka envelope từ outbox payload");
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getCause()).isInstanceOf(JsonProcessingException.class);
                });
        verifyNoInteractions(template);
    }

    @Test
    void missingBrokerAcknowledgementIsRetryableTimeout() {
        when(template.send(anyRecord())).thenReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> publisher.publish(event("{}", "trace-001"), Duration.ofMillis(5)))
                .isInstanceOfSatisfying(KafkaPublishException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("KAFKA_ACK_TIMEOUT");
                    assertThat(failure.getMessage()).isEqualTo("Kafka chưa xác nhận event trong thời gian cho phép");
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.getCause()).isInstanceOf(TimeoutException.class);
                });
    }

    @Test
    void interruptedWaitKeepsTheInterruptFlagAndIsRetryable() {
        when(template.send(anyRecord())).thenReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publish(event("{}", "trace-001"), ACK_TIMEOUT))
                    .isInstanceOfSatisfying(KafkaPublishException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("KAFKA_PUBLISH_INTERRUPTED");
                        assertThat(failure.getMessage()).isEqualTo("Luồng publish Kafka bị ngắt");
                        assertThat(failure.retryable()).isTrue();
                    });
        } finally {
            // Thread.interrupted() đọc và xóa cờ để không rò sang test khác.
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Test
    void kafkaExceptionsThrownDirectlyAreClassifiedLikeTheLegacyPublishers() {
        doThrow(new AuthenticationException("credential rejected")).when(template).send(anyRecord());
        assertFailure(KafkaPublishFailure.REJECTED, "KAFKA_PUBLISH_REJECTED",
                "Kafka từ chối event do cấu hình hoặc dữ liệu", false);

        doThrow(new SerializationException("cannot serialize")).when(template).send(anyRecord());
        assertFailure(KafkaPublishFailure.REJECTED, "KAFKA_PUBLISH_REJECTED",
                "Kafka từ chối event do cấu hình hoặc dữ liệu", false);

        doThrow(new org.apache.kafka.common.errors.TimeoutException("metadata")).when(template).send(anyRecord());
        assertFailure(KafkaPublishFailure.TEMPORARILY_UNAVAILABLE, "KAFKA_TEMPORARILY_UNAVAILABLE",
                "Kafka tạm thời chưa nhận event", true);

        doThrow(new IllegalStateException("producer closed")).when(template).send(anyRecord());
        assertFailure(KafkaPublishFailure.FAILED, "KAFKA_PUBLISH_FAILED", "Không publish được event sang Kafka", true);
    }

    @Test
    void asyncFailureIsClassifiedByTheCompletionCauseWithoutUnwrappingSpringExceptions() {
        CompletableFuture<SendResult<String, String>> rawFailure = new CompletableFuture<>();
        rawFailure.completeExceptionally(new RecordTooLargeException("too large"));
        doReturn(rawFailure).when(template).send(anyRecord());
        assertFailure(KafkaPublishFailure.REJECTED, "KAFKA_PUBLISH_REJECTED",
                "Kafka từ chối event do cấu hình hoặc dữ liệu", false);

        // KafkaTemplate bọc lỗi của callback trong KafkaProducerException: bản cũ không bóc lớp nên ra FAILED.
        CompletableFuture<SendResult<String, String>> wrapped = new CompletableFuture<>();
        wrapped.completeExceptionally(new KafkaProducerException(new ProducerRecord<>(TOPIC, "v"), "Failed to send",
                new RecordTooLargeException("too large")));
        doReturn(wrapped).when(template).send(anyRecord());
        assertFailure(KafkaPublishFailure.FAILED, "KAFKA_PUBLISH_FAILED", "Không publish được event sang Kafka", true);
    }

    @Test
    void failureCodesMessagesAndRetryFlagsMatchTheOutboxContract() {
        assertThat(Arrays.stream(KafkaPublishFailure.values())
                .map(failure -> failure.name() + "|" + failure.code() + "|" + failure.message() + "|"
                        + failure.retryable()))
                .containsExactly(
                        "PAYLOAD_NOT_OBJECT|OUTBOX_PAYLOAD_INVALID|Outbox payload phải là JSON object|false",
                        "PAYLOAD_UNREADABLE|OUTBOX_PAYLOAD_INVALID|Không thể tạo Kafka envelope từ outbox payload|false",
                        "INTERRUPTED|KAFKA_PUBLISH_INTERRUPTED|Luồng publish Kafka bị ngắt|true",
                        "ACK_TIMEOUT|KAFKA_ACK_TIMEOUT|Kafka chưa xác nhận event trong thời gian cho phép|true",
                        "REJECTED|KAFKA_PUBLISH_REJECTED|Kafka từ chối event do cấu hình hoặc dữ liệu|false",
                        "TEMPORARILY_UNAVAILABLE|KAFKA_TEMPORARILY_UNAVAILABLE|Kafka tạm thời chưa nhận event|true",
                        "FAILED|KAFKA_PUBLISH_FAILED|Không publish được event sang Kafka|true");
    }

    private void assertFailure(KafkaPublishFailure expected, String code, String message, boolean retryable) {
        assertThatThrownBy(() -> publisher.publish(event("{}", "trace-001"), ACK_TIMEOUT))
                .isInstanceOfSatisfying(KafkaPublishException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(expected);
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.getMessage()).isEqualTo(message);
                    assertThat(failure.retryable()).isEqualTo(retryable);
                    assertThat(failure.getCause()).isNotNull();
                });
    }

    private static KafkaOutgoingEvent event(String payload, String traceId) {
        return new KafkaOutgoingEvent(TOPIC, "LA-001", EVENT_ID, "LoanTermsAuthorized", 1,
                Instant.parse("2026-09-21T00:00:00Z"), payload, traceId);
    }

    private ProducerRecord<String, String> sentRecord() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        return captor.getValue();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static ProducerRecord<String, String> anyRecord() {
        return any();
    }
}
