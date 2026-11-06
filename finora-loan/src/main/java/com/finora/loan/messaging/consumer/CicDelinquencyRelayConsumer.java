package com.finora.loan.messaging.consumer;

import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.loan.integration.cic.client.CicDelinquencyRelayHttpClient;
import com.finora.loan.messaging.event.LoanDelinquencyChangedEventData;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Relay riêng cho CIC Vercel: Kafka vẫn là nguồn, HTTPS là kênh giao tới serverless. */
@Component
@ConditionalOnProperty(prefix = "finora.cic.delinquency-relay", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class CicDelinquencyRelayConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("LoanDelinquencyChanged", 1);

    private final KafkaEventReader kafkaEventReader;
    private final CicDelinquencyRelayHttpClient cicDelinquencyRelayHttpClient;

    @KafkaListener(
            topics = "${finora.cic.delinquency-relay.topic}",
            groupId = "${finora.cic.delinquency-relay.group-id}",
            containerFactory = "cicDelinquencyKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<LoanDelinquencyChangedEventData> event =
                kafkaEventReader.read(record, CONTRACT, LoanDelinquencyChangedEventData.class);
        validateData(event.data());
        // Gửi đúng envelope đã đọc, không dựng lại: occurredAt phải giữ nguyên chuỗi producer đã ghi.
        cicDelinquencyRelayHttpClient.relay(event.envelope());
    }

    private static void validateData(LoanDelinquencyChangedEventData data) {
        if (data == null || data.loanApplicationId() == null || data.loanApplicationId() <= 0
                || data.fineractLoanId() == null || data.fineractLoanId() <= 0
                || data.loanNumber() == null || data.loanNumber().isBlank()
                || data.borrowerId() == null || data.borrowerId().isBlank()
                || data.previousDaysPastDue() < 0 || data.daysPastDue() < 0
                || data.previousDebtGroup() < 1 || data.previousDebtGroup() > 5
                || data.debtGroup() < 1 || data.debtGroup() > 5
                || data.dataAsOf() == null || negative(data.overdueAmount())
                || negative(data.totalOutstanding())) {
            throw new IllegalArgumentException("LoanDelinquencyChanged data không hợp lệ");
        }
    }

    private static boolean negative(String amount) {
        try {
            return amount == null || new BigDecimal(amount).signum() < 0;
        } catch (NumberFormatException exception) {
            return true;
        }
    }
}
