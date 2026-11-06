package com.finora.loan.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.loan.messaging.event.DisbursementFailedEventData;
import com.finora.loan.service.disbursement.DisbursementSagaService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DisbursementFailedConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("DisbursementFailed", 1);

    private final KafkaEventReader kafkaEventReader;
    private final DisbursementSagaService disbursementSagaService;

    @KafkaListener(topics = "${finora.loan.kafka.disbursement-failed-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<DisbursementFailedEventData> event =
                kafkaEventReader.read(record, CONTRACT, DisbursementFailedEventData.class);
        disbursementSagaService.handleFailed(event.eventId(), event.occurredAt(), event.data());
    }
}
