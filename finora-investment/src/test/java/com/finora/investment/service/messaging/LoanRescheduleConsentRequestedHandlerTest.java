package com.finora.investment.service.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.investment.domain.note.InvestmentNote;
import com.finora.investment.messaging.event.InvestorServicingNotificationEventData;
import com.finora.investment.messaging.event.LoanRescheduleConsentRequestedEventData;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.InvestorRescheduleConsentRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * P7 (W3F, D78): mở phiếu đồng thuận cơ cấu thì mời từng chủ phiếu qua {@code InvestorNoteServicingChanged.v1}; giữ quy
 * tắc chống trùng của đường servicing (processed event + phiếu đã có cho {@code requestId}).
 */
class LoanRescheduleConsentRequestedHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final InvestmentNoteRepository notes = mock(InvestmentNoteRepository.class);
    private final InvestorRescheduleConsentRepository consents = mock(InvestorRescheduleConsentRepository.class);
    private final InvestmentOutboxService outbox = mock(InvestmentOutboxService.class);
    private final LoanRescheduleConsentRequestedHandler handler = new LoanRescheduleConsentRequestedHandler(
            processed, notes, consents, outbox, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void openingBallotsInvitesEachBallotHolderOnceWithPushAndNoPersonalData() {
        // Dựng mock Note trước khi stub repository (Mockito không cho stub lồng nhau).
        List<InvestmentNote> activeNotes = List.of(note("INVESTOR-A", "1000000.00"), note("INVESTOR-B", "500000.00"),
                note("INVESTOR-A", "1000000.00"), note("INVESTOR-C", "0.00"));
        when(notes.findByLoanIdAndStatusIn(eq(10L), any())).thenReturn(activeNotes);

        handler.handle(UUID.randomUUID(), NOW, request());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).record(eq("LOAN"), eq("10"), eq("InvestorNoteServicingChanged"), eq(1), payload.capture());
        InvestorServicingNotificationEventData event = (InvestorServicingNotificationEventData) payload.getValue();
        assertThat(event.changeType()).isEqualTo("RESCHEDULE_CONSENT_REQUESTED");
        assertThat(event.loanApplicationId()).isEqualTo(10L);
        assertThat(event.loanNumber()).isEqualTo("LN-10");
        assertThat(event.externalPushRequired()).isTrue();
        assertThat(event.maturityDate()).isEqualTo(LocalDate.of(2027, 8, 1));
        assertThat(event.changedAt()).isEqualTo(NOW.minusSeconds(30));
        // Mỗi chủ phiếu một người nhận (A giữ hai Note vẫn một phiếu); Note đã hết dư nợ không có phiếu.
        assertThat(event.recipients()).extracting(InvestorServicingNotificationEventData.Recipient::investorId)
                .containsExactly("INVESTOR-A", "INVESTOR-B");
        assertThat(event.recipients()).allSatisfy(recipient -> {
            assertThat(recipient.noteId()).isNull();
            assertThat(recipient.noteNumber()).isNull();
            assertThat(recipient.amount()).isNull();
        });
        verify(consents).saveAll(any());
        verify(processed).save(any());
    }

    @Test
    void loanReplayingTheSameRequestDoesNotInviteAgain() {
        // Loan phát lại cùng requestId với eventId mới: phiếu đã có thì không tạo phiếu, không mời lần hai.
        when(consents.existsByRequestId(REQUEST_ID)).thenReturn(true);

        handler.handle(UUID.randomUUID(), NOW, request());

        verify(outbox, never()).record(anyString(), anyString(), anyString(), anyInt(), any());
        verify(consents, never()).saveAll(any());
        verify(processed).save(any());
    }

    @Test
    void duplicateDeliveryOfTheSameEventDoesNothing() {
        UUID eventId = UUID.randomUUID();
        when(processed.existsByEventId(eventId)).thenReturn(true);

        handler.handle(eventId, NOW, request());

        verify(outbox, never()).record(anyString(), anyString(), anyString(), anyInt(), any());
        verify(processed, never()).save(any());
    }

    private static LoanRescheduleConsentRequestedEventData request() {
        return new LoanRescheduleConsentRequestedEventData(REQUEST_ID, 10L, "LN-10", "TERM_EXTENSION",
                LocalDate.of(2026, 11, 1), null, 2, LocalDate.of(2027, 8, 1), NOW.minusSeconds(30));
    }

    private static InvestmentNote note(String investorId, String outstandingPrincipal) {
        InvestmentNote note = mock(InvestmentNote.class);
        when(note.getInvestorId()).thenReturn(investorId);
        when(note.getOutstandingPrincipal()).thenReturn(new BigDecimal(outstandingPrincipal));
        return note;
    }
}
