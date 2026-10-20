package com.finora.notification.messaging.consumer;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Khoản trả kỳ trước hạn đang giữ (D16) và lúc bị hủy: {@code RepaymentCollected.v1}/{@code RepaymentCancelled.v1}, đề
 * xuất cho Payment (P5, plan W3F). Consumer viết sẵn theo contract đề xuất nhưng chỉ khởi động cùng nhắc lịch
 * ({@code NOTIFICATION_DUE_REMINDER_ENABLED=true}) để không tạo topic rỗng trên broker khi Payment chưa phát.
 */
@Component
@RequiredArgsConstructor
public class HeldInstallmentConsumer {

    private final KafkaEventProcessor processor;

    @KafkaListener(id = "notification-held-installments",
            groupId = "${finora.notification.kafka.domain-group:notification-domain-events}",
            autoStartup = "${finora.notification.due-reminder.enabled:false}",
            topics = {
                    "${finora.notification.kafka.topics.repayment-collected:finora.payment.repayment-collected}",
                    "${finora.notification.kafka.topics.repayment-cancelled:finora.payment.repayment-cancelled}"
            })
    public void consume(@Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(name = "finora-event-id", required = false) String eventId,
            @Header(name = "finora-event-type", required = false) String eventType,
            @Header(name = "finora-event-version", required = false) String eventVersion) {
        processor.process("notification-domain-events", topic, partition, offset, payload, eventId, eventType,
                eventVersion);
    }
}
