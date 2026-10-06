package com.finora.payment.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.payment.service.servicing.PaymentServicingProjectionService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoanDisbursedConsumer {
    private final ObjectMapper mapper;
    private final PaymentServicingProjectionService service;

    @KafkaListener(topics = "${finora.payment.loan-disbursed-topic}", groupId = "${finora.payment.servicing-group:payment-servicing}")
    public void consume(@Payload String payload,
            @Header(name = "finora-event-type", required = false) String type) {
        if (type != null && !"LoanDisbursed".equals(type)) throw new IllegalArgumentException("Sai event type");
        try {
            KafkaEventEnvelope envelope = mapper.readValue(payload, KafkaEventEnvelope.class);
            if (envelope.version() != 1) throw new IllegalArgumentException("LoanDisbursed version không hỗ trợ");
            service.activateLoan(envelope.eventId(), mapper.treeToValue(envelope.data(), LoanDisbursedEventData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("LoanDisbursed JSON không hợp lệ", exception);
        }
    }
}
