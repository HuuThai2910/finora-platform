package com.finora.loan.service.servicing.impl;

import com.finora.loan.domain.servicing.entity.PendingScheduledRepayment;
import com.finora.loan.messaging.event.RepaymentCancelledEventData;
import com.finora.loan.messaging.event.RepaymentCollectedEventData;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.repository.servicing.PendingScheduledRepaymentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PendingScheduledRepaymentHandlerImplTest {
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final PendingScheduledRepaymentRepository pending = mock(PendingScheduledRepaymentRepository.class);
    private final PendingScheduledRepaymentHandlerImpl handler = new PendingScheduledRepaymentHandlerImpl(
            processed, pending, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void collectedProjectsTheDueDateLoanMustSkip() {
        UUID eventId = UUID.randomUUID();
        UUID repaymentId = UUID.randomUUID();
        handler.collected(eventId, NOW.minusSeconds(10), new RepaymentCollectedEventData(
                repaymentId, 42L, "borrower-1", "SCHEDULED", "1000000.00", "VND",
                LocalDate.of(2026, 11, 25), NOW.minusSeconds(10)));

        ArgumentCaptor<PendingScheduledRepayment> value = ArgumentCaptor.forClass(PendingScheduledRepayment.class);
        verify(pending).save(value.capture());
        assertThat(value.getValue().getLoanApplicationId()).isEqualTo(42L);
        assertThat(value.getValue().getDueDate()).isEqualTo(LocalDate.of(2026, 11, 25));
        verify(processed).save(any());
    }

    @Test
    void cancelledRemovesOnlyTheMatchingPendingPayment() {
        UUID repaymentId = UUID.randomUUID();
        handler.cancelled(UUID.randomUUID(), NOW, new RepaymentCancelledEventData(
                repaymentId, 42L, "borrower-1", LocalDate.of(2026, 11, 25), NOW));

        verify(pending).deleteByLoanApplicationIdAndRepaymentId(42L, repaymentId);
        verify(processed).save(any());
    }
}
