package com.finora.loan.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.messaging.event.DisbursementCompletedEventData;
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
public class DisbursementCompletedConsumer {
    private final ObjectMapper objectMapper;
    private final DisbursementSagaService service;

    @KafkaListener(topics = "${finora.loan.kafka.disbursement-completed-topic}")
    public void consume(@Payload String payload,
            @Header(name = "finora-event-id", required = false) String id,
            @Header(name = "finora-event-type", required = false) String type,
            @Header(name = "finora-event-version", required = false) String version) {
        try {
            KafkaEventEnvelope envelope = objectMapper.readValue(payload, KafkaEventEnvelope.class);
            require(envelope, id, type, version);
            service.handleCompleted(envelope.eventId(), Instant.parse(envelope.occurredAt()),
                    objectMapper.treeToValue(envelope.data(), DisbursementCompletedEventData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("DisbursementCompleted không đúng JSON contract", exception);
        }
    }

    private void require(KafkaEventEnvelope e, String id, String type, String version) {
        if (e == null || e.eventId() == null || e.data() == null || e.occurredAt() == null || e.version() != 1
                || (id != null && !UUID.fromString(id).equals(e.eventId()))
                || (type != null && !"DisbursementCompleted".equals(type))
                || (version != null && Integer.parseInt(version) != 1)) {
            throw new IllegalArgumentException("DisbursementCompleted envelope/header không hợp lệ");
        }
    }
}

