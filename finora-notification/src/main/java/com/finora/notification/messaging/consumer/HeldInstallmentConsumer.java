package com.finora.notification.messaging.consumer;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Khoản trả kỳ trước hạn đang giữ (D16) và lúc bị hủy: {@code RepaymentCollected.v1}/{@code RepaymentCancelled.v1}, đề
 * xuất cho Payment (P5, plan W3F). Consumer viết sẵn theo contract đề xuất nhưng chỉ khởi động cùng nhắc lịch
 * ({@code NOTIFICATION_DUE_REMINDER_ENABLED=true}) để không tạo topic rỗng trên broker khi Payment chưa phát.
 */
@Component
@RequiredArgsConstructor
public class HeldInstallmentConsumer {

    private final KafkaEventProcessor kafkaEventProcessor;

    @KafkaListener(id = "notification-held-installments",
            groupId = "${finora.notification.kafka.domain-group:notification-domain-events}",
            autoStartup = "${finora.notification.due-reminder.enabled:false}",
            topics = {
                    "${finora.notification.kafka.topics.repayment-collected:finora.payment.repayment-collected}",
                    "${finora.notification.kafka.topics.repayment-cancelled:finora.payment.repayment-cancelled}"
            })
    public void consume(ConsumerRecord<String, String> record) {
        kafkaEventProcessor.process("notification-domain-events", record);
    }
}
