package com.finora.investment.messaging.producer;

import com.finora.common.messaging.KafkaEventPublisher;
import com.finora.common.messaging.KafkaOutgoingEvent;
import com.finora.common.messaging.KafkaPublishException;
import com.finora.investment.config.InvestmentKafkaProperties;
import com.finora.investment.messaging.event.NoteTransferDocumentIssuedEventData;
import com.finora.investment.service.outbox.InvestmentOutboxMessage;
import com.finora.investment.service.outbox.InvestmentOutboxPublishException;
import com.finora.investment.service.outbox.InvestmentOutboxPublisher;
import lombok.RequiredArgsConstructor;

/**
 * Publisher allowlist cho các event mà Investment sở hữu.
 * <p>
 * Chỉ chọn topic theo allowlist type/version rồi gửi bằng {@link KafkaEventPublisher} của finora-common; lỗi gửi đổi
 * sang {@link InvestmentOutboxPublishException} cùng mã, cờ thử lại và cause, với thông báo giữ đúng chữ cũ của
 * Investment để {@code last_error_detail} không đổi.
 */
@RequiredArgsConstructor
public class InvestmentKafkaOutboxPublisher implements InvestmentOutboxPublisher {

    private final KafkaEventPublisher kafkaEventPublisher;
    private final InvestmentKafkaProperties investmentKafkaProperties;

    @Override
    public void publish(InvestmentOutboxMessage message) {
        boolean fullyFunded = "LoanFullyFunded".equals(message.eventType()) && message.eventVersion() == 2;
        boolean fundingExpired = "LoanFundingExpired".equals(message.eventType()) && message.eventVersion() == 1;
        boolean ownershipChanged = "NoteOwnershipChanged".equals(message.eventType()) && message.eventVersion() == 1;
        boolean servicingChanged = "InvestorNoteServicingChanged".equals(message.eventType())
                && message.eventVersion() == 1;
        boolean rescheduleConsentCompleted = "LoanRescheduleConsentCompleted".equals(message.eventType())
                && message.eventVersion() == 1;
        boolean transferDocumentIssued = NoteTransferDocumentIssuedEventData.EVENT_TYPE.equals(message.eventType())
                && message.eventVersion() == NoteTransferDocumentIssuedEventData.EVENT_VERSION;
        if (!fullyFunded && !fundingExpired && !ownershipChanged && !servicingChanged
                && !rescheduleConsentCompleted && !transferDocumentIssued) {
            throw new InvestmentOutboxPublishException("KAFKA_EVENT_ROUTE_MISSING",
                    "Event/version chưa có Kafka route được duyệt", false, null);
        }
        String topic = switch (message.eventType()) {
            case "NoteOwnershipChanged" -> investmentKafkaProperties.noteOwnershipChangedTopic();
            case "InvestorNoteServicingChanged" -> investmentKafkaProperties.noteServicingChangedTopic();
            case "LoanFundingExpired" -> investmentKafkaProperties.fundingExpiredTopic();
            case "LoanRescheduleConsentCompleted" -> investmentKafkaProperties.rescheduleConsentCompletedTopic();
            case NoteTransferDocumentIssuedEventData.EVENT_TYPE -> investmentKafkaProperties.noteTransferDocumentIssuedTopic();
            default -> investmentKafkaProperties.fullyFundedTopic();
        };
        try {
            kafkaEventPublisher.publish(new KafkaOutgoingEvent(topic, message.aggregateId(), message.eventId(),
                    message.eventType(), message.eventVersion(), message.occurredAt(), message.payloadJson(),
                    message.traceId()), investmentKafkaProperties.publishTimeout());
        } catch (KafkaPublishException exception) {
            throw new InvestmentOutboxPublishException(exception.code(), legacyMessage(exception),
                    exception.retryable(), exception.getCause());
        }
    }

    /** Ba thông báo Investment viết khác Loan; các loại lỗi còn lại trùng chữ với bộ chung. */
    private static String legacyMessage(KafkaPublishException exception) {
        return switch (exception.failure()) {
            case ACK_TIMEOUT -> "Kafka chưa xác nhận trong thời gian cho phép";
            case REJECTED -> "Kafka từ chối event do cấu hình/dữ liệu";
            case PAYLOAD_UNREADABLE -> "Không thể tạo Kafka envelope";
            default -> exception.getMessage();
        };
    }
}
