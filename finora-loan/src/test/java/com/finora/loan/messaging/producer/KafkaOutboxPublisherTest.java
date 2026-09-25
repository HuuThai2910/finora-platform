package com.finora.loan.messaging.producer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.config.KafkaPublisherProperties;
import com.finora.loan.service.outbox.OutboxMessage;
import com.finora.loan.service.outbox.OutboxPublishException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaOutboxPublisherTest {

    private static final String TOPIC = "finora.loan.loan-terms-authorized";
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void publishesStableEnvelopeWithAggregateKeyAndTraceHeaders() throws Exception {
        KafkaTemplate<String, String> template = template();
        when(template.send(anyProducerRecord())).thenReturn(CompletableFuture.completedFuture(null));
        KafkaOutboxPublisher publisher = publisher(template, Duration.ofSeconds(1));
        OutboxMessage message = message("{\"applicationNumber\":\"LA-001\"}");

        publisher.publish(message);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        ProducerRecord<String, String> sent = captor.getValue();
        assertThat(sent.topic()).isEqualTo(TOPIC);
        assertThat(sent.key()).isEqualTo("LA-001");
        JsonNode envelope = objectMapper.readTree(sent.value());
        assertThat(envelope.path("eventId").asText()).isEqualTo(message.eventId().toString());
        assertThat(envelope.path("occurredAt").asText()).isEqualTo("2026-09-21T00:00:00Z");
        assertThat(envelope.path("version").asInt()).isEqualTo(1);
        assertThat(envelope.path("data").path("applicationNumber").asText()).isEqualTo("LA-001");
        assertThat(header(sent, KafkaOutboxPublisher.EVENT_TYPE_HEADER)).isEqualTo("LoanTermsAuthorized");
        assertThat(header(sent, KafkaOutboxPublisher.EVENT_VERSION_HEADER)).isEqualTo("1");
        assertThat(header(sent, KafkaOutboxPublisher.EVENT_ID_HEADER)).isEqualTo(message.eventId().toString());
        assertThat(header(sent, KafkaOutboxPublisher.TRACE_ID_HEADER)).isEqualTo("trace-001");
    }

    @Test
    void rejectsUnapprovedEventRouteWithoutCallingBroker() {
        KafkaTemplate<String, String> template = template();
        KafkaOutboxPublisher publisher = publisher(template, Duration.ofSeconds(1));
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "LoanContract", "LC-001", "LoanContractSigned", 1,
                "{}", "trace-001", Instant.parse("2026-09-21T00:00:00Z"));

        assertThatThrownBy(() -> publisher.publish(message))
                .isInstanceOfSatisfying(OutboxPublishException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo("KAFKA_EVENT_ROUTE_MISSING");
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @Test
    void rejectsInvalidPayloadAsNonRetryable() {
        KafkaTemplate<String, String> template = template();
        KafkaOutboxPublisher publisher = publisher(template, Duration.ofSeconds(1));

        assertThatThrownBy(() -> publisher.publish(message("[]")))
                .isInstanceOfSatisfying(OutboxPublishException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo("OUTBOX_PAYLOAD_INVALID");
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @Test
    void classifiesBrokerAuthenticationFailureAsNonRetryable() {
        KafkaTemplate<String, String> template = template();
        when(template.send(anyProducerRecord()))
                .thenThrow(new AuthenticationException("credential rejected"));
        KafkaOutboxPublisher publisher = publisher(template, Duration.ofSeconds(1));

        assertThatThrownBy(() -> publisher.publish(message("{}")))
                .isInstanceOfSatisfying(OutboxPublishException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo("KAFKA_PUBLISH_REJECTED");
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @Test
    void treatsMissingBrokerAcknowledgementAsRetryable() {
        KafkaTemplate<String, String> template = template();
        when(template.send(anyProducerRecord())).thenReturn(new CompletableFuture<>());
        KafkaOutboxPublisher publisher = publisher(template, Duration.ofMillis(5));

        assertThatThrownBy(() -> publisher.publish(message("{}")))
                .isInstanceOfSatisfying(OutboxPublishException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo("KAFKA_ACK_TIMEOUT");
                    assertThat(failure.retryable()).isTrue();
                });
    }

    private KafkaOutboxPublisher publisher(KafkaTemplate<String, String> template, Duration timeout) {
        KafkaPublisherProperties properties = new KafkaPublisherProperties(
                timeout,
                List.of(new KafkaPublisherProperties.EventRoute(
                        "LoanTermsAuthorized", 1, TOPIC))
        );
        return new KafkaOutboxPublisher(template, objectMapper, properties);
    }

    private OutboxMessage message(String payload) {
        return new OutboxMessage(
                UUID.fromString("47bcf45e-07ba-42d3-b119-856b6ffd0868"),
                "LoanApplication", "LA-001", "LoanTermsAuthorized", 1,
                payload, "trace-001", Instant.parse("2026-09-21T00:00:00Z")
        );
    }

    private String header(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private ProducerRecord<String, String> anyProducerRecord() {
        return any();
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> template() {
        return mock(KafkaTemplate.class);
    }
}
