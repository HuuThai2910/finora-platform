package com.finora.investment.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.messaging.event.KafkaEventEnvelope;
import com.finora.investment.messaging.event.LoanRescheduledEventData;
import com.finora.investment.messaging.event.LoanSettledEventData;
import com.finora.investment.messaging.event.LoanDelinquencyChangedEventData;
import com.finora.investment.service.messaging.LoanServicingLifecycleEventHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoanServicingLifecycleConsumer {
    private final ObjectMapper mapper;
    private final LoanServicingLifecycleEventHandler handler;

    @KafkaListener(topics = "${finora.investment.kafka.loan-settled-topic}")
    public void settled(@Payload String payload,
            @Header(name = "finora-event-id", required = false) String id,
            @Header(name = "finora-event-type", required = false) String type,
            @Header(name = "finora-event-version", required = false) String version) {
        KafkaEventEnvelope envelope = envelope(payload, id, type, version, "LoanSettled");
        try {
            handler.settled(envelope.eventId(), Instant.parse(envelope.occurredAt()),
                    mapper.treeToValue(envelope.data(), LoanSettledEventData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("LoanSettled JSON không hợp lệ", exception);
        }
    }

    @KafkaListener(topics = "${finora.investment.kafka.loan-rescheduled-topic}")
    public void rescheduled(@Payload String payload,
            @Header(name = "finora-event-id", required = false) String id,
            @Header(name = "finora-event-type", required = false) String type,
            @Header(name = "finora-event-version", required = false) String version) {
        KafkaEventEnvelope envelope = envelope(payload, id, type, version, "LoanRescheduled");
        try {
            handler.rescheduled(envelope.eventId(), Instant.parse(envelope.occurredAt()),
                    mapper.treeToValue(envelope.data(), LoanRescheduledEventData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("LoanRescheduled JSON không hợp lệ", exception);
        }
    }

    @KafkaListener(topics = "${finora.investment.kafka.loan-delinquency-changed-topic}")
    public void delinquencyChanged(@Payload String payload,
            @Header(name = "finora-event-id", required = false) String id,
            @Header(name = "finora-event-type", required = false) String type,
            @Header(name = "finora-event-version", required = false) String version) {
        KafkaEventEnvelope envelope = envelope(payload, id, type, version, "LoanDelinquencyChanged");
        try {
            handler.delinquencyChanged(envelope.eventId(), Instant.parse(envelope.occurredAt()),
                    mapper.treeToValue(envelope.data(), LoanDelinquencyChangedEventData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("LoanDelinquencyChanged JSON không hợp lệ", exception);
        }
    }

    private KafkaEventEnvelope envelope(String payload, String id, String type, String version,
            String expectedType) {
        try {
            KafkaEventEnvelope envelope = mapper.readValue(payload, KafkaEventEnvelope.class);
            if (envelope == null || envelope.eventId() == null || envelope.data() == null
                    || envelope.occurredAt() == null || envelope.version() != 1
                    || (id != null && !UUID.fromString(id).equals(envelope.eventId()))
                    || (type != null && !expectedType.equals(type))
                    || (version != null && Integer.parseInt(version) != 1)) {
                throw new IllegalArgumentException(expectedType + " envelope/header không hợp lệ");
            }
            return envelope;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(expectedType + " envelope không hợp lệ", exception);
        }
    }
}
