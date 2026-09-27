package com.finora.loan.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.messaging.event.DisbursementFailedEventData;
import com.finora.loan.service.disbursement.DisbursementSagaService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DisbursementFailedConsumer {
    private final ObjectMapper objectMapper;
    private final DisbursementSagaService service;

    @KafkaListener(topics = "${finora.loan.kafka.disbursement-failed-topic}")
    public void consume(@Payload String payload,
            @Header(name = "finora-event-id", required = false) String id,
            @Header(name = "finora-event-type", required = false) String type,
            @Header(name = "finora-event-version", required = false) String version) {
        try {
            KafkaEventEnvelope envelope = objectMapper.readValue(payload, KafkaEventEnvelope.class);
            require(envelope, id, type, version);
            service.handleFailed(envelope.eventId(), Instant.parse(envelope.occurredAt()),
                    objectMapper.treeToValue(envelope.data(), DisbursementFailedEventData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("DisbursementFailed không đúng JSON contract", exception);
        }
    }

    private void require(KafkaEventEnvelope e, String id, String type, String version) {
        if (e == null || e.eventId() == null || e.data() == null || e.occurredAt() == null || e.version() != 1
                || (id != null && !UUID.fromString(id).equals(e.eventId()))
                || (type != null && !"DisbursementFailed".equals(type))
                || (version != null && Integer.parseInt(version) != 1)) {
            throw new IllegalArgumentException("DisbursementFailed envelope/header không hợp lệ");
        }
    }
}
