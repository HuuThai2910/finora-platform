package com.finora.payment.messaging.consumer;

import com.finora.common.messaging.FinoraKafkaHeaders;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.payment.messaging.event.LoanDisbursedEventData;
import com.finora.payment.service.servicing.PaymentServicingProjectionService;
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

/** {@code LoanDisbursed} giữ mức kiểm cũ: chỉ header type và version 1; eventId, occurredAt, data không bắt buộc. */
class LoanDisbursedConsumerTest {

    private final PaymentServicingProjectionService paymentServicingProjectionService =
            mock(PaymentServicingProjectionService.class);
    private final LoanDisbursedConsumer consumer = new LoanDisbursedConsumer(
            new KafkaEventReader(Jackson2ObjectMapperBuilder.json().build()), paymentServicingProjectionService);

    @Test
    void readsDataAndIgnoresFieldsLoanAddedLater() {
        UUID eventId = UUID.randomUUID();
        consumer.consume(record("""
                {"eventId":"%s","occurredAt":"2026-10-01T00:00:01Z","version":1,
                 "data":{"loanApplicationId":561,"fineractLoanId":761,"disbursedAt":"2026-10-01T00:00:00Z",
                         "borrowerId":"BORROWER-561","nextDueAmount":"1726000.00"}}
                """.formatted(eventId), eventId.toString(), "LoanDisbursed", "1"));

        ArgumentCaptor<LoanDisbursedEventData> data = ArgumentCaptor.forClass(LoanDisbursedEventData.class);
        verify(paymentServicingProjectionService).activateLoan(eq(eventId), data.capture());
        assertThat(data.getValue().fineractLoanId()).isEqualTo(761L);
    }

    @Test
    void onlyHeaderTypeAndVersionAreCheckedLikeBefore() {
        // Không eventId, occurredAt, data; header id/version lệch envelope: vẫn giao cho handler như trước khi chuyển.
        consumer.consume(record("{\"version\":1}", UUID.randomUUID().toString(), "LoanDisbursed", "7"));

        verify(paymentServicingProjectionService).activateLoan(null, null);
    }

    @Test
    void wrongHeaderTypeIsRejectedBeforeReadingJsonAndOtherVersionsAreRejected() {
        assertThatThrownBy(() -> consumer.consume(record("{broken", null, "NoteOwnershipChanged", null)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(FinoraKafkaHeaders.EVENT_TYPE);
        assertThatThrownBy(() -> consumer.consume(record("{\"version\":2,\"data\":{}}", null, null, null)))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        verify(paymentServicingProjectionService, never()).activateLoan(any(), any());
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
