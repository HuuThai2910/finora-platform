package com.finora.investment.service.messaging.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.investment.domain.note.InvestmentLoanServicingState;
import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.messaging.event.LoanDelinquencyChangedEventData;
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
class LoanServicingLifecycleEventHandlerImplTest {
    private static final Instant NOW = Instant.parse("2026-10-04T07:00:00Z");
    @Mock InvestmentLoanServicingStateRepository states;
    @Mock ProcessedEventRepository processed;
    @Mock InvestmentNoteRepository notes;
    @Mock InvestmentOutboxService outbox;
    private LoanServicingLifecycleEventHandlerImpl handler;

    @BeforeEach
    void setUp() {
        handler = new LoanServicingLifecycleEventHandlerImpl(states, processed, notes, outbox,
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
    void nhomNoXauChuyenNoteSangDefaulted() {
        when(states.findByApplicationIdForUpdate(10L)).thenReturn(Optional.empty());

        handler.delinquencyChanged(UUID.randomUUID(), NOW, delinquency(95, 2, 3, NOW));

        verify(notes).transitionStatusByLoan(10L, NoteStatus.ACTIVE, NoteStatus.DEFAULTED, NOW);
    }

    @Test
    void nhomNoVeLaiNhom2TraNoteVeActive() {
        var state = InvestmentLoanServicingState.delinquency(10L, "LN-10", 95, 3,
                new java.math.BigDecimal("100.00"), null, new java.math.BigDecimal("1000.00"),
                NOW.minusSeconds(3600), NOW.minusSeconds(3600), NOW.minusSeconds(3600));
        when(states.findByApplicationIdForUpdate(10L)).thenReturn(Optional.of(state));

        handler.delinquencyChanged(UUID.randomUUID(), NOW, delinquency(0, 3, 1, NOW));

        verify(notes).transitionStatusByLoan(10L, NoteStatus.DEFAULTED, NoteStatus.ACTIVE, NOW);
    }

    @Test
    void suKienQuaHanDenMuonKhongDoiTrangThaiNote() {
        var state = InvestmentLoanServicingState.delinquency(10L, "LN-10", 0, 1,
                java.math.BigDecimal.ZERO, null, new java.math.BigDecimal("1000.00"),
                NOW, NOW, NOW);
        when(states.findByApplicationIdForUpdate(10L)).thenReturn(Optional.of(state));

        handler.delinquencyChanged(UUID.randomUUID(), NOW, delinquency(95, 2, 3, NOW.minusSeconds(60)));

        verify(notes, never()).transitionStatusByLoan(any(), any(), any(), any());
    }

    private LoanDelinquencyChangedEventData delinquency(int dpd, int previousGroup, int group, Instant asOf) {
        return new LoanDelinquencyChangedEventData(10L, "LN-10", "BORROWER-10", 20L, 0, dpd,
                previousGroup, group, dpd == 0 ? "0.00" : "100.00", null, "1000.00", asOf);
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
