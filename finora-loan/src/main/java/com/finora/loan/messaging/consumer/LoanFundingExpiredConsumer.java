package com.finora.loan.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.messaging.event.LoanFundingExpiredEventData;
import com.finora.loan.service.funding.LoanFundingExpiredHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** Chỉ nhận `LoanFundingExpired.v1`; contract sai vào DLT thay vì bị bỏ qua. */
@Component
@ConditionalOnProperty(name = "finora.loan.kafka.consumer-enabled", havingValue = "true")
@RequiredArgsConstructor
public class LoanFundingExpiredConsumer {

    private static final String EVENT_TYPE = "LoanFundingExpired";
    private static final int EVENT_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final LoanFundingExpiredHandler handler;

    @KafkaListener(topics = "${finora.loan.kafka.funding-expired-topic}")
    public void consume(
            @Payload String payload,
            @Header(name = "finora-event-id", required = false) String eventIdHeader,
            @Header(name = "finora-event-type", required = false) String eventTypeHeader,
            @Header(name = "finora-event-version", required = false) String eventVersionHeader
    ) {
        try {
            KafkaEventEnvelope envelope = objectMapper.readValue(payload, KafkaEventEnvelope.class);
            if (envelope == null || envelope.eventId() == null || envelope.data() == null
                    || envelope.occurredAt() == null || envelope.version() != EVENT_VERSION
                    || (eventTypeHeader != null && !EVENT_TYPE.equals(eventTypeHeader))
                    || (eventVersionHeader != null && Integer.parseInt(eventVersionHeader) != EVENT_VERSION)
                    || (eventIdHeader != null && !UUID.fromString(eventIdHeader).equals(envelope.eventId()))) {
                throw new IllegalArgumentException("LoanFundingExpired envelope/header không hợp lệ");
            }
            handler.handle(envelope.eventId(), Instant.parse(envelope.occurredAt()),
                    objectMapper.treeToValue(envelope.data(), LoanFundingExpiredEventData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("LoanFundingExpired không đúng JSON contract", exception);
        }
    }
}
