package com.finora.investment.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.messaging.event.InvestorSignatureRequestedEventData;
import com.finora.investment.messaging.event.KafkaEventEnvelope;
import com.finora.investment.service.messaging.ContractLifecycleEventHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InvestorSignatureRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final ContractLifecycleEventHandler handler;

    @KafkaListener(topics = "${finora.investment.kafka.investor-signature-requested-topic}")
    public void consume(
            @Payload String payload,
            @Header(name = "finora-event-id", required = false) String eventIdHeader,
            @Header(name = "finora-event-type", required = false) String eventTypeHeader,
            @Header(name = "finora-event-version", required = false) String eventVersionHeader
    ) {
        try {
            KafkaEventEnvelope envelope = objectMapper.readValue(payload, KafkaEventEnvelope.class);
            requireEnvelope(envelope, eventIdHeader, eventTypeHeader, eventVersionHeader);
            handler.handleSignatureRequested(
                    envelope.eventId(), Instant.parse(envelope.occurredAt()),
                    objectMapper.treeToValue(envelope.data(), InvestorSignatureRequestedEventData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("InvestorSignatureRequested không đúng JSON contract", exception);
        }
    }

    private void requireEnvelope(KafkaEventEnvelope envelope, String id, String type, String version) {
        if (envelope == null || envelope.eventId() == null || envelope.data() == null
                || envelope.occurredAt() == null || envelope.version() != 1
                || (id != null && !UUID.fromString(id).equals(envelope.eventId()))
                || (type != null && !"InvestorSignatureRequested".equals(type))
                || (version != null && Integer.parseInt(version) != 1)) {
            throw new IllegalArgumentException("InvestorSignatureRequested envelope/header không hợp lệ");
        }
    }
}
