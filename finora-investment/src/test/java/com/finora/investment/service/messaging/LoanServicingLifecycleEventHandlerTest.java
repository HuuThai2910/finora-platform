package com.finora.investment.service.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.investment.domain.note.InvestmentLoanServicingState;
import com.finora.investment.messaging.event.LoanRescheduledEventData;
import com.finora.investment.messaging.event.LoanSettledEventData;
import com.finora.investment.repository.InvestmentLoanServicingStateRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanServicingLifecycleEventHandlerTest {
    private static final Instant NOW = Instant.parse("2026-10-04T07:00:00Z");
    @Mock InvestmentLoanServicingStateRepository states;
    @Mock ProcessedEventRepository processed;
    @Mock InvestmentNoteRepository notes;
    @Mock InvestmentOutboxService outbox;
    private LoanServicingLifecycleEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LoanServicingLifecycleEventHandler(states, processed, notes, outbox,
                Clock.fixed(NOW, ZoneOffset.UTC));
        org.mockito.Mockito.lenient().when(notes.findByLoanIdAndStatusIn(any(), any()))
                .thenReturn(java.util.List.of());
    }

    @Test
    void createsRescheduleProjectionAndProcessedMarkerAtomically() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(states.findByApplicationIdForUpdate(10L)).thenReturn(Optional.empty());
        handler.rescheduled(eventId, NOW, new LoanRescheduledEventData(
                10L, "LN-10", "BORROWER-10", 20L, requestId, "TERM_EXTENSION",
                LocalDate.of(2027, 4, 4), LocalDate.of(2027, 7, 4),
                LocalDate.of(2026, 11, 4), LocalDate.of(2026, 12, 4), 3, NOW));

        ArgumentCaptor<InvestmentLoanServicingState> state =
                ArgumentCaptor.forClass(InvestmentLoanServicingState.class);
        verify(states).save(state.capture());
        assertThat(state.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(state.getValue().getMaturityDate()).isEqualTo(LocalDate.of(2027, 7, 4));
        assertThat(state.getValue().getLastRescheduleRequestId()).isEqualTo(requestId);
        verify(processed).save(any());
    }

    @Test
    void duplicateSettlementDoesNotApplyTwice() {
        UUID eventId = UUID.randomUUID();
        when(processed.existsByEventId(eventId)).thenReturn(true);
        handler.settled(eventId, NOW, new LoanSettledEventData(
                10L, "LN-10", "LC-10", "BORROWER-10", 20L, NOW));

        verify(states, never()).findByApplicationIdForUpdate(any());
        verify(states, never()).save(any());
        verify(processed, never()).save(any());
    }
}
