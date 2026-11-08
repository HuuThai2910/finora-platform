package com.finora.loan.messaging.consumer;

import com.finora.common.messaging.*;
import com.finora.loan.messaging.event.RepaymentCancelledEventData;
import com.finora.loan.service.servicing.PendingScheduledRepaymentHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RepaymentCancelledConsumer {
    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("RepaymentCancelled", 1);
    private final KafkaEventReader reader;
    private final PendingScheduledRepaymentHandler handler;

    @KafkaListener(topics = "${finora.loan.kafka.repayment-cancelled-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<RepaymentCancelledEventData> event = reader.read(record, CONTRACT, RepaymentCancelledEventData.class);
        handler.cancelled(event.eventId(), event.occurredAt(), event.data());
    }
}
