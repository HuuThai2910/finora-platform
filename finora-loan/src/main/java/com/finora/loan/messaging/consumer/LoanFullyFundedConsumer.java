package com.finora.loan.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.messaging.event.LoanFullyFundedEventData;
import com.finora.loan.service.funding.LoanFullyFundedHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** Chỉ nhận `LoanFullyFunded.v2`; contract sai sẽ vào DLT thay vì bị bỏ qua. */
@Component
@ConditionalOnProperty(name = "finora.loan.kafka.consumer-enabled", havingValue = "true")
@RequiredArgsConstructor
public class LoanFullyFundedConsumer {

    private static final String EVENT_TYPE = "LoanFullyFunded";
    private static final int EVENT_VERSION = 2;

    private final ObjectMapper objectMapper;
    private final LoanFullyFundedHandler handler;

    @KafkaListener(topics = "${finora.loan.kafka.fully-funded-topic}")
    public void consume(
            @Payload String payload,
            @Header(name = "finora-event-id", required = false) String eventIdHeader,
            @Header(name = "finora-event-type", required = false) String eventTypeHeader,
            @Header(name = "finora-event-version", required = false) String eventVersionHeader
    ) {
        try {
            KafkaEventEnvelope envelope = objectMapper.readValue(payload, KafkaEventEnvelope.class);
            requireContract(envelope, eventIdHeader, eventTypeHeader, eventVersionHeader);
            LoanFullyFundedEventData data = objectMapper.treeToValue(
                    envelope.data(), LoanFullyFundedEventData.class);
            handler.handle(envelope.eventId(), Instant.parse(envelope.occurredAt()), data);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("LoanFullyFunded không đúng JSON contract", exception);
        }
    }

    private void requireContract(
            KafkaEventEnvelope envelope,
            String eventIdHeader,
            String eventTypeHeader,
            String eventVersionHeader
    ) {
        if (envelope == null || envelope.eventId() == null || envelope.data() == null
                || envelope.occurredAt() == null || envelope.version() != EVENT_VERSION) {
            throw new IllegalArgumentException("LoanFullyFunded envelope/version không hợp lệ");
        }
        if (eventTypeHeader != null && !EVENT_TYPE.equals(eventTypeHeader)) {
            throw new IllegalArgumentException("Kafka event type không khớp LoanFullyFunded");
        }
        if (eventVersionHeader != null && Integer.parseInt(eventVersionHeader) != EVENT_VERSION) {
            throw new IllegalArgumentException("Kafka event version header không hợp lệ");
        }
        if (eventIdHeader != null && !UUID.fromString(eventIdHeader).equals(envelope.eventId())) {
            throw new IllegalArgumentException("Kafka eventId header không khớp envelope");
        }
    }
}
