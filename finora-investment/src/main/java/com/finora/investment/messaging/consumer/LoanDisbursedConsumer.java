package com.finora.investment.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.investment.messaging.event.LoanDisbursedEventData;
import com.finora.investment.service.messaging.LoanDisbursedEventHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoanDisbursedConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("LoanDisbursed", 1);

    private final KafkaEventReader kafkaEventReader;
    private final LoanDisbursedEventHandler loanDisbursedEventHandler;

    @KafkaListener(topics = "${finora.investment.kafka.loan-disbursed-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanDisbursedEventData> event = kafkaEventReader.read(record, CONTRACT, LoanDisbursedEventData.class);
        loanDisbursedEventHandler.handle(event.eventId(), event.occurredAt(), event.data());
    }
}
