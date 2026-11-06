package com.finora.investment.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.investment.messaging.event.LoanContractActivatedEventData;
import com.finora.investment.service.messaging.ContractLifecycleEventHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoanContractActivatedConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("LoanContractActivated", 1);

    private final KafkaEventReader kafkaEventReader;
    private final ContractLifecycleEventHandler contractLifecycleEventHandler;

    @KafkaListener(topics = "${finora.investment.kafka.contract-activated-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanContractActivatedEventData> event = kafkaEventReader.read(record, CONTRACT, LoanContractActivatedEventData.class);
        contractLifecycleEventHandler.handleActivated(event.eventId(), event.occurredAt(), event.data());
    }
}
