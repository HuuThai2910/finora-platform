package com.finora.loan.messaging.consumer;

import com.finora.common.messaging.*;
import com.finora.loan.messaging.event.RepaymentCollectedEventData;
import com.finora.loan.service.servicing.PendingScheduledRepaymentHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RepaymentCollectedConsumer {
    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("RepaymentCollected", 1);
    private final KafkaEventReader reader;
    private final PendingScheduledRepaymentHandler handler;

    @KafkaListener(topics = "${finora.loan.kafka.repayment-collected-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<RepaymentCollectedEventData> event = reader.read(record, CONTRACT, RepaymentCollectedEventData.class);
        handler.collected(event.eventId(), event.occurredAt(), event.data());
    }
}
