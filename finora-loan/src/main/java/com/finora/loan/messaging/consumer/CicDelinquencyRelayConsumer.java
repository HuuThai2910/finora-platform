package com.finora.loan.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.integration.cic.CicDelinquencyRelayHttpClient;
import com.finora.loan.messaging.event.LoanDelinquencyChangedEventData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** Relay riêng cho CIC Vercel: Kafka vẫn là nguồn, HTTPS là kênh giao tới serverless. */
@Component
@ConditionalOnProperty(prefix = "finora.cic.delinquency-relay", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class CicDelinquencyRelayConsumer {
    private final ObjectMapper mapper;
    private final CicDelinquencyRelayHttpClient httpClient;

    @KafkaListener(
            topics = "${finora.cic.delinquency-relay.topic}",
            groupId = "${finora.cic.delinquency-relay.group-id}",
            containerFactory = "cicDelinquencyKafkaListenerContainerFactory")
    public void consume(
            @Payload String payload,
            @Header(name = "finora-event-id", required = false) String id,
            @Header(name = "finora-event-type", required = false) String type,
            @Header(name = "finora-event-version", required = false) String version
    ) {
        try {
            KafkaEventEnvelope envelope = mapper.readValue(payload, KafkaEventEnvelope.class);
            validateEnvelope(envelope, id, type, version);
            LoanDelinquencyChangedEventData data =
                    mapper.treeToValue(envelope.data(), LoanDelinquencyChangedEventData.class);
            validateData(data);
            httpClient.relay(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("LoanDelinquencyChanged JSON không hợp lệ", exception);
        }
    }

    private static void validateEnvelope(
            KafkaEventEnvelope envelope, String id, String type, String version
    ) {
        if (envelope == null || envelope.eventId() == null || envelope.occurredAt() == null
                || envelope.data() == null || envelope.version() != 1) {
            throw new IllegalArgumentException("LoanDelinquencyChanged envelope không hợp lệ");
        }
        try {
            Instant.parse(envelope.occurredAt());
            if (id != null && !UUID.fromString(id).equals(envelope.eventId())) {
                throw new IllegalArgumentException("Header eventId không khớp envelope");
            }
            if (version != null && Integer.parseInt(version) != 1) {
                throw new IllegalArgumentException("Header event version không hợp lệ");
            }
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalArgumentException("LoanDelinquencyChanged header/thời điểm không hợp lệ", exception);
        }
        if (type != null && !"LoanDelinquencyChanged".equals(type)) {
            throw new IllegalArgumentException("Header event type không hợp lệ");
        }
    }

    private static void validateData(LoanDelinquencyChangedEventData data) {
        if (data == null || data.loanApplicationId() == null || data.loanApplicationId() <= 0
                || data.fineractLoanId() == null || data.fineractLoanId() <= 0
                || data.loanNumber() == null || data.loanNumber().isBlank()
                || data.borrowerId() == null || data.borrowerId().isBlank()
                || data.previousDaysPastDue() < 0 || data.daysPastDue() < 0
                || data.previousDebtGroup() < 1 || data.previousDebtGroup() > 5
                || data.debtGroup() < 1 || data.debtGroup() > 5
                || data.dataAsOf() == null || negative(data.overdueAmount())
                || negative(data.totalOutstanding())) {
            throw new IllegalArgumentException("LoanDelinquencyChanged data không hợp lệ");
        }
    }

    private static boolean negative(String amount) {
        try {
            return amount == null || new BigDecimal(amount).signum() < 0;
        } catch (NumberFormatException exception) {
            return true;
        }
    }
}
