package com.finora.investment.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.investment.messaging.event.LoanFundingRequestedEventData;
import com.finora.investment.service.messaging.LoanFundingRequestedHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Nhận đúng `LoanFundingRequested.v1`; type/version khác bị fail closed để vào retry/DLT. */
@Component
@RequiredArgsConstructor
public class LoanFundingRequestedConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("LoanFundingRequested", 1);

    private final KafkaEventReader kafkaEventReader;
    private final LoanFundingRequestedHandler loanFundingRequestedHandler;

    @KafkaListener(topics = "${finora.investment.kafka.funding-requested-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanFundingRequestedEventData> event = kafkaEventReader.read(record, CONTRACT, LoanFundingRequestedEventData.class);
        loanFundingRequestedHandler.handle(event.eventId(), event.occurredAt(), event.data());
    }
}
