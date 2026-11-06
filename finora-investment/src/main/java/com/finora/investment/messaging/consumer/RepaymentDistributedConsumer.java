package com.finora.investment.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.investment.messaging.event.RepaymentDistributedEventData;
import com.finora.investment.service.messaging.RepaymentDistributedEventHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RepaymentDistributedConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("RepaymentDistributed", 1);

    private final KafkaEventReader kafkaEventReader;
    private final RepaymentDistributedEventHandler repaymentDistributedEventHandler;

    @KafkaListener(topics = "${finora.investment.kafka.repayment-distributed-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<RepaymentDistributedEventData> event = kafkaEventReader.read(record, CONTRACT, RepaymentDistributedEventData.class);
        repaymentDistributedEventHandler.handle(event.eventId(), event.occurredAt(), event.data());
    }
}
