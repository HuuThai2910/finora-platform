package com.finora.investment.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.messaging.event.KafkaEventEnvelope;
import com.finora.investment.messaging.event.RepaymentDistributedEventData;
import com.finora.investment.service.messaging.RepaymentDistributedEventHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RepaymentDistributedConsumer {
    private final ObjectMapper mapper;
    private final RepaymentDistributedEventHandler handler;

    @KafkaListener(topics = "${finora.investment.kafka.repayment-distributed-topic}")
    public void consume(@Payload String payload,
            @Header(name = "finora-event-id", required = false) String id,
            @Header(name = "finora-event-type", required = false) String type,
            @Header(name = "finora-event-version", required = false) String version) {
        try {
            KafkaEventEnvelope envelope = mapper.readValue(payload, KafkaEventEnvelope.class);
            if (envelope == null || envelope.eventId() == null || envelope.data() == null
                    || envelope.occurredAt() == null || envelope.version() != 1
                    || (id != null && !UUID.fromString(id).equals(envelope.eventId()))
                    || (type != null && !"RepaymentDistributed".equals(type))
                    || (version != null && Integer.parseInt(version) != 1)) {
                throw new IllegalArgumentException("RepaymentDistributed envelope/header không hợp lệ");
            }
            handler.handle(envelope.eventId(), Instant.parse(envelope.occurredAt()),
                    mapper.treeToValue(envelope.data(), RepaymentDistributedEventData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("RepaymentDistributed JSON không hợp lệ", exception);
        }
    }
}
