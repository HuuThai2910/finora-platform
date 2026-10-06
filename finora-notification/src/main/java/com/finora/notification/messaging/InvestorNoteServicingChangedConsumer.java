package com.finora.notification.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.notification.service.InvestorServicingNotificationService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InvestorNoteServicingChangedConsumer {
    private final ObjectMapper mapper;
    private final InvestorServicingNotificationService service;

    @KafkaListener(topics = "${finora.notification.kafka.note-servicing-changed-topic}")
    public void consume(@Payload String payload,
            @Header(name = "finora-event-id", required = false) String headerId,
            @Header(name = "finora-event-type", required = false) String type,
            @Header(name = "finora-event-version", required = false) String version) {
        try {
            NotificationKafkaEnvelope envelope = mapper.readValue(payload, NotificationKafkaEnvelope.class);
            if (envelope.eventId() == null || envelope.occurredAt() == null
                    || envelope.occurredAt().isBlank() || envelope.data() == null
                    || envelope.version() != 1
                    || (headerId != null && !UUID.fromString(headerId).equals(envelope.eventId()))
                    || (type != null && !"InvestorNoteServicingChanged".equals(type))
                    || (version != null && Integer.parseInt(version) != 1)) {
                throw new IllegalArgumentException("InvestorNoteServicingChanged envelope/header không hợp lệ");
            }
            service.handle(envelope.eventId(), Instant.parse(envelope.occurredAt()),
                    mapper.treeToValue(envelope.data(), InvestorNoteServicingChangedData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("InvestorNoteServicingChanged JSON không hợp lệ", exception);
        }
    }
}
