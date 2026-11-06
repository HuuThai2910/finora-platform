package com.finora.payment.messaging.consumer;

import com.finora.common.messaging.FinoraKafkaHeaders;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.payment.messaging.event.DisbursementRequestedEventData;
import com.finora.payment.service.disbursement.PaymentDisbursementService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Hợp đồng chặt của {@code DisbursementRequested.v2}: envelope đủ trường, header nếu có phải khớp envelope. */
class DisbursementRequestedConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("7d2b6c3e-8f4a-4c1e-9b5d-2a6f0e1c3b47");
    private static final UUID SAGA_ID = UUID.fromString("0f6c1d2e-3b4a-4f5e-8d7c-9a1b2c3d4e5f");
    private static final String OCCURRED_AT = "2026-10-01T00:00:00Z";

    private final PaymentDisbursementService paymentDisbursementService = mock(PaymentDisbursementService.class);
    private final DisbursementRequestedConsumer consumer = new DisbursementRequestedConsumer(
            new KafkaEventReader(Jackson2ObjectMapperBuilder.json().build()), paymentDisbursementService);

    @Test
    void validVersionTwoEventReachesTheServiceWithItsEventIdAndData() {
        consumer.consume(record(envelope(2, OCCURRED_AT), EVENT_ID.toString(), "DisbursementRequested", "2"));

        ArgumentCaptor<DisbursementRequestedEventData> data =
                ArgumentCaptor.forClass(DisbursementRequestedEventData.class);
        verify(paymentDisbursementService).request(eq(EVENT_ID), data.capture());
        assertThat(data.getValue().sagaId()).isEqualTo(SAGA_ID);
        assertThat(data.getValue().allocations()).singleElement()
                .satisfies(allocation -> assertThat(allocation.paymentHoldReference()).isEqualTo("HOLD-1"));
    }

    @Test
    void otherVersionsMissingFieldsAndDisagreeingHeadersNeverReachTheService() {
        assertRejected(record(envelope(1, OCCURRED_AT), null, null, null));
        assertRejected(record(envelope(2, null), null, null, null));
        assertRejected(record(envelope(2, OCCURRED_AT), UUID.randomUUID().toString(), null, null));
        assertRejected(record(envelope(2, OCCURRED_AT), null, "LoanDisbursed", null));
        assertRejected(record(envelope(2, OCCURRED_AT), null, null, "1"));
        assertRejected(record("{broken", null, null, null));
        verify(paymentDisbursementService, never()).request(any(), any());
    }

    /** Sai hợp đồng phải là đúng {@link IllegalArgumentException}: error handler không retry và đưa vào DLT. */
    private void assertRejected(ConsumerRecord<String, String> record) {
        assertThatThrownBy(() -> consumer.consume(record)).isExactlyInstanceOf(IllegalArgumentException.class);
    }

    private static String envelope(int version, String occurredAt) {
        String occurred = occurredAt == null ? "" : "\"occurredAt\":\"" + occurredAt + "\",";
        return """
                {"eventId":"%s",%s"version":%d,"data":{"sagaId":"%s","loanApplicationId":561,
                 "applicationNumber":"LA-561","contractNumber":"LC-561","listingId":661,"borrowerId":"BORROWER-561",
                 "amount":"10000000.00","currency":"VND","allocations":[{"commitmentId":1,"investorId":"INVESTOR-1",
                 "amount":"10000000.00","paymentHoldReference":"HOLD-1"}]}}
                """.formatted(EVENT_ID, occurred, version, SAGA_ID);
    }

    private static ConsumerRecord<String, String> record(String payload, String id, String type, String version) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("finora.test", 0, 0L, null, payload);
        addHeader(record, FinoraKafkaHeaders.EVENT_ID, id);
        addHeader(record, FinoraKafkaHeaders.EVENT_TYPE, type);
        addHeader(record, FinoraKafkaHeaders.EVENT_VERSION, version);
        return record;
    }

    private static void addHeader(ConsumerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
