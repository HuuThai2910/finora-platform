package com.finora.investment.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.investment.messaging.event.LoanDelinquencyChangedEventData;
import com.finora.investment.messaging.event.LoanRescheduledEventData;
import com.finora.investment.messaging.event.LoanSettledEventData;
import com.finora.investment.service.messaging.LoanServicingLifecycleEventHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoanServicingLifecycleConsumer {

    private static final KafkaEventContract SETTLED = KafkaEventContract.of("LoanSettled", 1);
    private static final KafkaEventContract RESCHEDULED = KafkaEventContract.of("LoanRescheduled", 1);
    private static final KafkaEventContract DELINQUENCY_CHANGED = KafkaEventContract.of("LoanDelinquencyChanged", 1);

    private final KafkaEventReader kafkaEventReader;
    private final LoanServicingLifecycleEventHandler loanServicingLifecycleEventHandler;

    @KafkaListener(topics = "${finora.investment.kafka.loan-settled-topic}")
    public void settled(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanSettledEventData> event = kafkaEventReader.read(record, SETTLED, LoanSettledEventData.class);
        loanServicingLifecycleEventHandler.settled(event.eventId(), event.occurredAt(), event.data());
    }

    @KafkaListener(topics = "${finora.investment.kafka.loan-rescheduled-topic}")
    public void rescheduled(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanRescheduledEventData> event =
                kafkaEventReader.read(record, RESCHEDULED, LoanRescheduledEventData.class);
        loanServicingLifecycleEventHandler.rescheduled(event.eventId(), event.occurredAt(), event.data());
    }

    @KafkaListener(topics = "${finora.investment.kafka.loan-delinquency-changed-topic}")
    public void delinquencyChanged(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanDelinquencyChangedEventData> event =
                kafkaEventReader.read(record, DELINQUENCY_CHANGED, LoanDelinquencyChangedEventData.class);
        loanServicingLifecycleEventHandler.delinquencyChanged(event.eventId(), event.occurredAt(), event.data());
    }
}
