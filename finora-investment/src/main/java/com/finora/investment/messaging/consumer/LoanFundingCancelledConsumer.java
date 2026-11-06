package com.finora.investment.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.investment.messaging.event.LoanFundingCancelledEventData;
import com.finora.investment.service.messaging.LoanFundingCancelledHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Nhận đúng `LoanFundingCancelled.v1`; type/version khác bị fail closed để vào retry/DLT. */
@Component
@RequiredArgsConstructor
public class LoanFundingCancelledConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("LoanFundingCancelled", 1);

    private final KafkaEventReader kafkaEventReader;
    private final LoanFundingCancelledHandler loanFundingCancelledHandler;

    @KafkaListener(topics = "${finora.investment.kafka.funding-cancelled-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanFundingCancelledEventData> event = kafkaEventReader.read(record, CONTRACT, LoanFundingCancelledEventData.class);
        loanFundingCancelledHandler.handle(event.eventId(), event.occurredAt(), event.data());
    }
}
