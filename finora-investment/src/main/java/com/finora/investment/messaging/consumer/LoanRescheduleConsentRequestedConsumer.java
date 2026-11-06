package com.finora.investment.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.investment.messaging.event.LoanRescheduleConsentRequestedEventData;
import com.finora.investment.service.messaging.LoanRescheduleConsentRequestedHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoanRescheduleConsentRequestedConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("LoanRescheduleConsentRequested", 1);

    private final KafkaEventReader kafkaEventReader;
    private final LoanRescheduleConsentRequestedHandler loanRescheduleConsentRequestedHandler;

    @KafkaListener(topics = "${finora.investment.kafka.reschedule-consent-requested-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanRescheduleConsentRequestedEventData> event = kafkaEventReader.read(record, CONTRACT, LoanRescheduleConsentRequestedEventData.class);
        loanRescheduleConsentRequestedHandler.handle(event.eventId(), event.occurredAt(), event.data());
    }
}
