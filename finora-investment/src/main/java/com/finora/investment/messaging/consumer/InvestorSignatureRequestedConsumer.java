package com.finora.investment.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.investment.messaging.event.InvestorSignatureRequestedEventData;
import com.finora.investment.service.messaging.ContractLifecycleEventHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InvestorSignatureRequestedConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("InvestorSignatureRequested", 1);

    private final KafkaEventReader kafkaEventReader;
    private final ContractLifecycleEventHandler contractLifecycleEventHandler;

    @KafkaListener(topics = "${finora.investment.kafka.investor-signature-requested-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<InvestorSignatureRequestedEventData> event = kafkaEventReader.read(record, CONTRACT, InvestorSignatureRequestedEventData.class);
        contractLifecycleEventHandler.handleSignatureRequested(event.eventId(), event.occurredAt(), event.data());
    }
}
