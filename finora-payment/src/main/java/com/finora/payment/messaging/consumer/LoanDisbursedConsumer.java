package com.finora.payment.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract.DataRule;
import com.finora.common.messaging.KafkaEventContract.HeaderRule;
import com.finora.common.messaging.KafkaEventContract.OccurredAtRule;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.payment.messaging.event.LoanDisbursedEventData;
import com.finora.payment.service.servicing.PaymentServicingProjectionService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoanDisbursedConsumer {

    /**
     * Giữ đúng mức kiểm cũ: chỉ header type (trước khi đọc JSON) và version 1; không kiểm header id/version, không bắt
     * buộc eventId, occurredAt, data ({@code data} thiếu hoặc {@code null} thì handler nhận {@code null}).
     */
    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("LoanDisbursed", 1)
            .withHeaderRule(HeaderRule.TYPE_ONLY)
            .withEventIdRequired(false)
            .withOccurredAtRule(OccurredAtRule.IGNORED)
            .withDataRule(DataRule.OPTIONAL);

    private final KafkaEventReader kafkaEventReader;
    private final PaymentServicingProjectionService paymentServicingProjectionService;

    @KafkaListener(topics = "${finora.payment.loan-disbursed-topic}", groupId = "${finora.payment.servicing-group:payment-servicing}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanDisbursedEventData> event = kafkaEventReader.read(record, CONTRACT, LoanDisbursedEventData.class);
        paymentServicingProjectionService.activateLoan(event.eventId(), event.data());
    }
}
