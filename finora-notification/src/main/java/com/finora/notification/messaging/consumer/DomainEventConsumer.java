package com.finora.notification.messaging.consumer;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Event nghiệp vụ của Loan, Investment và Payment mà Notification thông báo (ma trận actor × event của plan W3F).
 * Group riêng {@code notification-domain-events}: group mới đọc topic từ đầu trong thời gian Kafka còn giữ, nên bảng tra
 * người nhận được dựng lại cho các khoản vay đang chạy; push của event cũ hơn 24 giờ bị chặn (D62).
 */
@Component
@RequiredArgsConstructor
public class DomainEventConsumer {

    private final KafkaEventProcessor processor;

    @KafkaListener(id = "notification-domain-events",
            groupId = "${finora.notification.kafka.domain-group:notification-domain-events}",
            topics = {
                    "${finora.notification.kafka.topics.loan-funding-requested:finora.loan.funding-requested}",
                    "${finora.notification.kafka.topics.loan-fully-funded:finora.investment.loan-fully-funded}",
                    "${finora.notification.kafka.topics.loan-funding-expired:finora.investment.loan-funding-expired}",
                    "${finora.notification.kafka.topics.loan-funding-cancelled:finora.loan.funding-cancelled}",
                    "${finora.notification.kafka.topics.investor-signature-requested:finora.loan.investor-signature-requested}",
                    "${finora.notification.kafka.topics.borrower-signature-requested:finora.loan.borrower-signature-requested}",
                    "${finora.notification.kafka.topics.loan-contract-activated:finora.loan.contract-activated}",
                    "${finora.notification.kafka.topics.loan-disbursed:finora.loan.disbursed}",
                    "${finora.notification.kafka.topics.repayment-distributed:finora.payment.repayment-distributed}",
                    "${finora.notification.kafka.topics.loan-delinquency-changed:finora.loan.delinquency-changed}",
                    "${finora.notification.kafka.topics.loan-settled:finora.loan.settled}",
                    "${finora.notification.kafka.topics.loan-reschedule-consent-requested:finora.loan.reschedule-consent-requested}",
                    "${finora.notification.kafka.topics.loan-reschedule-consent-completed:finora.investment.reschedule-consent-completed}",
                    "${finora.notification.kafka.topics.loan-rescheduled:finora.loan.rescheduled}",
                    "${finora.notification.kafka.topics.withdrawal-completed:finora.payment.withdrawal-completed}",
                    "${finora.notification.kafka.topics.withdrawal-failed:finora.payment.withdrawal-failed}"
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
