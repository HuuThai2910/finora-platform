package com.finora.payment.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.payment.messaging.event.DisbursementRequestedEventData;
import com.finora.payment.service.disbursement.PaymentDisbursementService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Nhận yêu cầu giải ngân của Loan ({@code DisbursementRequested.v2}). Hợp đồng chặt: envelope phải có eventId,
 * occurredAt, data; header nếu có phải khớp envelope. Sai hợp đồng ném {@link IllegalArgumentException} và vào DLT ngay.
 */
@Component
@RequiredArgsConstructor
public class DisbursementRequestedConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("DisbursementRequested", 2);

    private final KafkaEventReader kafkaEventReader;
    private final PaymentDisbursementService paymentDisbursementService;

    @KafkaListener(topics = "${finora.payment.disbursement-requested-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<DisbursementRequestedEventData> event =
                kafkaEventReader.read(record, CONTRACT, DisbursementRequestedEventData.class);
        paymentDisbursementService.request(event.eventId(), event.data());
    }
}
