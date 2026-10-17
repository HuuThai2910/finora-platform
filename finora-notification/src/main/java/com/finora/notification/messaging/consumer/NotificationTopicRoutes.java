package com.finora.notification.messaging.consumer;

import com.finora.notification.config.NotificationProperties;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Topic → loại event duy nhất được phép trên topic đó (mỗi topic FINORA mang một loại event). Header
 * {@code finora-event-type} khác loại này là lỗi contract.
 */
@Component
public class NotificationTopicRoutes {

    private final Map<String, String> typeByTopic = new HashMap<>();

    public NotificationTopicRoutes(NotificationProperties properties) {
        NotificationProperties.Topics topics = properties.kafka().topics();
        route(topics.loanFundingRequested(), "LoanFundingRequested");
        route(topics.loanFullyFunded(), "LoanFullyFunded");
        route(topics.loanFundingExpired(), "LoanFundingExpired");
        route(topics.loanFundingCancelled(), "LoanFundingCancelled");
        route(topics.investorSignatureRequested(), "InvestorSignatureRequested");
        route(topics.borrowerSignatureRequested(), "BorrowerSignatureRequested");
        route(topics.loanContractActivated(), "LoanContractActivated");
        route(topics.loanDisbursed(), "LoanDisbursed");
        route(topics.repaymentDistributed(), "RepaymentDistributed");
        route(topics.loanDelinquencyChanged(), "LoanDelinquencyChanged");
        route(topics.loanSettled(), "LoanSettled");
        route(topics.loanRescheduleConsentRequested(), "LoanRescheduleConsentRequested");
        route(topics.loanRescheduleConsentCompleted(), "LoanRescheduleConsentCompleted");
        route(topics.loanRescheduled(), "LoanRescheduled");
        route(topics.withdrawalCompleted(), "WithdrawalCompleted");
        route(topics.withdrawalFailed(), "WithdrawalFailed");
        route(topics.repaymentCollected(), "RepaymentCollected");
        route(topics.repaymentCancelled(), "RepaymentCancelled");
        route(properties.kafka().noteServicingChangedTopic(), "InvestorNoteServicingChanged");
    }

    public String expectedType(String topic) {
        String type = typeByTopic.get(topic);
        if (type == null) throw new IllegalArgumentException("Notification không đăng ký topic " + topic);
        return type;
    }

    private void route(String topic, String eventType) {
        String previous = typeByTopic.putIfAbsent(topic, eventType);
        if (previous != null && !previous.equals(eventType)) {
            throw new IllegalStateException("Topic " + topic + " được cấu hình cho hai loại event");
        }
    }
}
