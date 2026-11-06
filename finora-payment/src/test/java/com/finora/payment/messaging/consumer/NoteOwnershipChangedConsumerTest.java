package com.finora.payment.messaging.consumer;

import com.finora.common.messaging.FinoraKafkaHeaders;
import com.finora.common.messaging.KafkaEventReader;
import com.finora.payment.messaging.event.NoteOwnershipChangedEventData;
import com.finora.payment.service.servicing.PaymentServicingProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** {@code NoteOwnershipChanged} giữ mức kiểm cũ như {@code LoanDisbursed}: chỉ header type và version 1. */
class NoteOwnershipChangedConsumerTest {

    private final PaymentServicingProjectionService paymentServicingProjectionService =
            mock(PaymentServicingProjectionService.class);
    private final NoteOwnershipChangedConsumer consumer = new NoteOwnershipChangedConsumer(
            new KafkaEventReader(Jackson2ObjectMapperBuilder.json().build()), paymentServicingProjectionService);

    @Test
    void eventWithoutEventIdOrOccurredAtStillReachesTheHandler() {
        consumer.consume(record("""
                {"version":1,"data":{"loanApplicationId":561,"listingId":661,"currency":"VND","reason":"TRANSFER",
                 "reference":"T-1","changedAt":"2026-10-01T00:00:00Z",
                 "notes":[{"noteId":1,"noteNumber":"N-1","investorId":"INVESTOR-1","outstandingPrincipal":"500.00"}]}}
                """, "NoteOwnershipChanged"));

        ArgumentCaptor<NoteOwnershipChangedEventData> data =
                ArgumentCaptor.forClass(NoteOwnershipChangedEventData.class);
        verify(paymentServicingProjectionService).updateOwnership(eq(null), data.capture());
        assertThat(data.getValue().notes()).singleElement()
                .satisfies(note -> assertThat(note.investorId()).isEqualTo("INVESTOR-1"));
    }

    @Test
    void wrongHeaderTypeAndOtherVersionsAreRejected() {
        assertThatThrownBy(() -> consumer.consume(record("{broken", "LoanDisbursed")))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.consume(record("{\"version\":2}", null)))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        verify(paymentServicingProjectionService, never()).updateOwnership(any(), any());
    }

    private static ConsumerRecord<String, String> record(String payload, String type) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("finora.test", 0, 0L, null, payload);
        if (type != null) {
            record.headers().add(FinoraKafkaHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
