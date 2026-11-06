package com.finora.payment.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract.DataRule;
import com.finora.common.messaging.KafkaEventContract.HeaderRule;
import com.finora.common.messaging.KafkaEventContract.OccurredAtRule;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.payment.messaging.event.NoteOwnershipChangedEventData;
import com.finora.payment.service.servicing.PaymentServicingProjectionService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NoteOwnershipChangedConsumer {

    /** Mức kiểm như {@code LoanDisbursedConsumer}: chỉ header type và version 1. */
    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("NoteOwnershipChanged", 1)
            .withHeaderRule(HeaderRule.TYPE_ONLY)
            .withEventIdRequired(false)
            .withOccurredAtRule(OccurredAtRule.IGNORED)
            .withDataRule(DataRule.OPTIONAL);

    private final KafkaEventReader kafkaEventReader;
    private final PaymentServicingProjectionService paymentServicingProjectionService;

    @KafkaListener(topics = "${finora.payment.note-ownership-changed-topic}", groupId = "${finora.payment.servicing-group:payment-servicing}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<NoteOwnershipChangedEventData> event =
                kafkaEventReader.read(record, CONTRACT, NoteOwnershipChangedEventData.class);
        paymentServicingProjectionService.updateOwnership(event.eventId(), event.data());
    }
}
