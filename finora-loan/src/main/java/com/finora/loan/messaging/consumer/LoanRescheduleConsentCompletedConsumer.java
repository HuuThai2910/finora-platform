package com.finora.loan.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.loan.messaging.event.LoanRescheduleConsentCompletedEventData;
import com.finora.loan.service.restructure.LoanRescheduleConsentResultHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "finora.loan.kafka.consumer-enabled", havingValue = "true")
@RequiredArgsConstructor
public class LoanRescheduleConsentCompletedConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("LoanRescheduleConsentCompleted", 1);

    private final KafkaEventReader kafkaEventReader;
    private final LoanRescheduleConsentResultHandler loanRescheduleConsentResultHandler;

    @KafkaListener(topics = "${finora.loan.kafka.reschedule-consent-completed-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanRescheduleConsentCompletedEventData> event =
                kafkaEventReader.read(record, CONTRACT, LoanRescheduleConsentCompletedEventData.class);
        loanRescheduleConsentResultHandler.handle(event.eventId(), event.occurredAt(), event.data());
    }
}
