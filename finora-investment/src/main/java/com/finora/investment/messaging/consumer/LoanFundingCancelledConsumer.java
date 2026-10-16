package com.finora.investment.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.messaging.event.KafkaEventEnvelope;
import com.finora.investment.messaging.event.LoanFundingCancelledEventData;
import com.finora.investment.service.messaging.LoanFundingCancelledHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** Nhận đúng `LoanFundingCancelled.v1`; type/version khác bị fail closed để vào retry/DLT. */
@Component
@RequiredArgsConstructor
public class LoanFundingCancelledConsumer {

    private static final String EVENT_TYPE = "LoanFundingCancelled";
    private static final int EVENT_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final LoanFundingCancelledHandler handler;

    @KafkaListener(topics = "${finora.investment.kafka.funding-cancelled-topic}")
    public void consume(
            @Payload String payload,
            @Header(name = "finora-event-id", required = false) String eventIdHeader,
            @Header(name = "finora-event-type", required = false) String eventTypeHeader,
            @Header(name = "finora-event-version", required = false) String eventVersionHeader
    ) {
        try {
            KafkaEventEnvelope envelope = objectMapper.readValue(payload, KafkaEventEnvelope.class);
            requireContract(envelope, eventIdHeader, eventTypeHeader, eventVersionHeader);
            LoanFundingCancelledEventData data = objectMapper.treeToValue(
                    envelope.data(), LoanFundingCancelledEventData.class);
            handler.handle(envelope.eventId(), Instant.parse(envelope.occurredAt()), data);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("LoanFundingCancelled không đúng JSON contract", exception);
        }
    }

    private void requireContract(
            KafkaEventEnvelope envelope,
            String eventIdHeader,
            String eventTypeHeader,
            String eventVersionHeader
    ) {
        if (envelope == null || envelope.eventId() == null || envelope.data() == null
                || envelope.version() != EVENT_VERSION) {
            throw new IllegalArgumentException("LoanFundingCancelled envelope/version không hợp lệ");
        }
        if (eventTypeHeader != null && !EVENT_TYPE.equals(eventTypeHeader)) {
            throw new IllegalArgumentException("Kafka event type không khớp LoanFundingCancelled");
        }
        if (eventVersionHeader != null && Integer.parseInt(eventVersionHeader) != EVENT_VERSION) {
            throw new IllegalArgumentException("Kafka event version header không hợp lệ");
        }
        if (eventIdHeader != null && !UUID.fromString(eventIdHeader).equals(envelope.eventId())) {
            throw new IllegalArgumentException("Kafka eventId header không khớp envelope");
        }
    }
}
