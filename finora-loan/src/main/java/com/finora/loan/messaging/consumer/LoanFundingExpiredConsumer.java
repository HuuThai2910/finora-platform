package com.finora.loan.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.loan.messaging.event.LoanFundingExpiredEventData;
import com.finora.loan.service.funding.LoanFundingExpiredHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Chỉ nhận `LoanFundingExpired.v1`; contract sai vào DLT thay vì bị bỏ qua. */
@Component
@ConditionalOnProperty(name = "finora.loan.kafka.consumer-enabled", havingValue = "true")
@RequiredArgsConstructor
public class LoanFundingExpiredConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("LoanFundingExpired", 1);

    private final KafkaEventReader kafkaEventReader;
    private final LoanFundingExpiredHandler loanFundingExpiredHandler;

    @KafkaListener(topics = "${finora.loan.kafka.funding-expired-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanFundingExpiredEventData> event =
                kafkaEventReader.read(record, CONTRACT, LoanFundingExpiredEventData.class);
        loanFundingExpiredHandler.handle(event.eventId(), event.occurredAt(), event.data());
    }
}
