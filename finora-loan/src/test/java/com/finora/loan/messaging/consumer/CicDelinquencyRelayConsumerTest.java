package com.finora.loan.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.loan.integration.cic.client.CicDelinquencyRelayHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CicDelinquencyRelayConsumerTest {
    private static final String EVENT_ID = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String PAYLOAD = """
            {
              "eventId":"aaaaaaaa-0000-4000-8000-000000000001",
              "occurredAt":"2026-10-03T03:00:00Z",
              "version":1,
              "data":{
                "loanApplicationId":88,
                "loanNumber":"LN-2026-000088",
                "borrowerId":"42",
                "fineractLoanId":99,
                "previousDaysPastDue":0,
                "daysPastDue":12,
                "previousDebtGroup":1,
                "debtGroup":2,
                "overdueAmount":"2500000.00",
                "overdueSince":"2026-09-21",
                "totalOutstanding":"30000000.00",
                "dataAsOf":"2026-10-03T03:00:00Z"
              }
            }
            """;

    private CicDelinquencyRelayHttpClient client;
    private CicDelinquencyRelayConsumer consumer;

    @BeforeEach
    void setUp() {
        client = mock(CicDelinquencyRelayHttpClient.class);
        consumer = new CicDelinquencyRelayConsumer(
                new KafkaEventReader(new ObjectMapper().findAndRegisterModules()), client);
    }

    @Test
    void relaysValidEnvelopeOnce() {
        consumer.consume(KafkaTestRecords.record(PAYLOAD, EVENT_ID, "LoanDelinquencyChanged", "1"));

        verify(client).relay(argThat(envelope -> EVENT_ID.equals(envelope.eventId().toString())
                && envelope.version() == 1));
    }

    @Test
    void rejectsMismatchedHeaderBeforeHttpCall() {
        assertThatThrownBy(() -> consumer.consume(KafkaTestRecords.record(
                PAYLOAD, "bbbbbbbb-0000-4000-8000-000000000002", "LoanDelinquencyChanged", "1")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void relaysOccurredAtExactlyAsProduced() {
        // CIC nhận nguyên envelope: occurredAt có offset không được đổi sang dạng Z khi chuyển tiếp.
        String withOffset = PAYLOAD.replace("\"occurredAt\":\"2026-10-03T03:00:00Z\"",
                "\"occurredAt\":\"2026-10-03T10:00:00+07:00\"");

        consumer.consume(KafkaTestRecords.record(withOffset, EVENT_ID, "LoanDelinquencyChanged", "1"));

        verify(client).relay(argThat(envelope -> "2026-10-03T10:00:00+07:00".equals(envelope.occurredAt())));
    }

    @Test
    void rejectsInvalidDomainPayloadBeforeHttpCall() {
        String invalid = PAYLOAD.replace("\"daysPastDue\":12", "\"daysPastDue\":-1");
        assertThatThrownBy(() -> consumer.consume(
                KafkaTestRecords.record(invalid, EVENT_ID, "LoanDelinquencyChanged", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data");
        verifyNoInteractions(client);
    }
}
