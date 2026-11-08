package com.finora.investment.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.investment.messaging.event.LoanFundingReopenedEventData;
import com.finora.investment.service.messaging.LoanFundingReopenedHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consume {@code LoanFundingReopened.v1}: nhả phần vốn của nhà đầu tư rời hợp đồng và đưa khoản vay lại lên sàn.
 */
@Component
@RequiredArgsConstructor
public class LoanFundingReopenedConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("LoanFundingReopened", 1);

    private final KafkaEventReader kafkaEventReader;
    private final LoanFundingReopenedHandler loanFundingReopenedHandler;

    @KafkaListener(topics = "${finora.investment.kafka.funding-reopened-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanFundingReopenedEventData> event =
                kafkaEventReader.read(record, CONTRACT, LoanFundingReopenedEventData.class);
        loanFundingReopenedHandler.handle(event.eventId(), event.occurredAt(), event.data());
    }
}
