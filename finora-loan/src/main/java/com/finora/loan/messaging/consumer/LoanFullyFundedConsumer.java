package com.finora.loan.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.loan.messaging.event.LoanFullyFundedEventData;
import com.finora.loan.service.funding.LoanFullyFundedHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Chỉ nhận `LoanFullyFunded.v2`; contract sai sẽ vào DLT thay vì bị bỏ qua. */
@Component
@ConditionalOnProperty(name = "finora.loan.kafka.consumer-enabled", havingValue = "true")
@RequiredArgsConstructor
public class LoanFullyFundedConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("LoanFullyFunded", 2);

    private final KafkaEventReader kafkaEventReader;
    private final LoanFullyFundedHandler loanFullyFundedHandler;

    @KafkaListener(topics = "${finora.loan.kafka.fully-funded-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanFullyFundedEventData> event =
                kafkaEventReader.read(record, CONTRACT, LoanFullyFundedEventData.class);
        loanFullyFundedHandler.handle(event.eventId(), event.occurredAt(), event.data());
    }
}
