package com.finora.investment.service.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.investment.domain.secondary.NoteRepaymentRedirect;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.messaging.event.InvestorServicingNotificationEventData;
import com.finora.investment.messaging.event.RepaymentDistributedEventData;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.NoteRepaymentRedirectRepository;
import com.finora.investment.repository.NoteTransferRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RepaymentDistributedEventHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-10T07:00:00Z");
    private static final UUID REPAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Mock InvestmentNoteRepository notes;
    @Mock ProcessedEventRepository processed;
    @Mock InvestmentOutboxService outbox;
    @Mock NoteTransferRepository transfers;
    @Mock NoteRepaymentRedirectRepository redirects;

    private RepaymentDistributedEventHandler handler;
    private InvestmentNote first;
    private InvestmentNote second;

    @BeforeEach
    void setUp() {
        handler = new RepaymentDistributedEventHandler(notes, processed, outbox, transfers, redirects,
                Clock.fixed(NOW, ZoneOffset.UTC));
        first = note(1L, "investor-a");
        second = note(2L, "investor-a");
        when(notes.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
        org.mockito.Mockito.lenient().when(notes.findByIdForUpdate(2L)).thenReturn(Optional.of(second));
        org.mockito.Mockito.lenient().when(notes.findAllById(any())).thenReturn(List.of(first, second));
    }

    @Test
    void thongBaoMangPhanTienCuaTungNoteChuKhongPhaiTongKhoanVay() {
        handler.handle(UUID.randomUUID(), NOW, event(
                allocation(1L, "investor-a", "100.00", "10.00"),
                allocation(2L, "investor-a", "100.00", "10.00")));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).record(anyString(), anyString(), eq("InvestorNoteServicingChanged"), anyInt(), payload.capture());
        var data = (InvestorServicingNotificationEventData) payload.getValue();
        assertThat(data.recipients()).extracting(InvestorServicingNotificationEventData.Recipient::amount)
                .containsExactly("110.00", "110.00");
        verify(redirects, never()).save(any());
    }

    @Test
    void paymentTraChoChuCuDaBanNoteThiGhiKhoanChuyenLaiChoChuMoi() {
        first.setInvestorId("buyer");
        when(transfers.existsByNoteIdAndSellerId(1L, "seller")).thenReturn(true);

        handler.handle(UUID.randomUUID(), NOW,
                event(allocation(1L, "seller", "100.00", "10.00", "2.00")));

        ArgumentCaptor<NoteRepaymentRedirect> redirect = ArgumentCaptor.forClass(NoteRepaymentRedirect.class);
        verify(redirects).save(redirect.capture());
        assertThat(redirect.getValue().getFromInvestorId()).isEqualTo("seller");
        assertThat(redirect.getValue().getToInvestorId()).isEqualTo("buyer");
        assertThat(redirect.getValue().getAmount()).isEqualByComparingTo("112.00");
        assertThat(redirect.getValue().getRedirectReference()).isEqualTo("REPAY-REDIRECT:" + REPAYMENT_ID + ":1");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).record(anyString(), anyString(), eq("InvestorNoteServicingChanged"), anyInt(), payload.capture());
        // Chủ mới được báo vì worker sẽ chuyển tiền cho họ.
        assertThat(((InvestorServicingNotificationEventData) payload.getValue()).recipients())
                .extracting(InvestorServicingNotificationEventData.Recipient::investorId)
                .containsExactly("buyer");
        assertThat(((InvestorServicingNotificationEventData) payload.getValue()).recipients())
                .extracting(InvestorServicingNotificationEventData.Recipient::amount)
                .containsExactly("112.00");
    }

    @Test
    void paymentTraChoNguoiChuaTungSoHuuNoteThiDungLai() {
        first.setInvestorId("buyer");
        when(transfers.existsByNoteIdAndSellerId(1L, "stranger")).thenReturn(false);

        assertThatThrownBy(() -> handler.handle(UUID.randomUUID(), NOW,
                event(allocation(1L, "stranger", "100.00", "10.00"))))
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("REPAYMENT_OWNER_MISMATCH");
        verify(processed, never()).save(any());
    }

    private InvestmentNote note(Long id, String investorId) {
        return InvestmentNote.builder()
                .id(id).noteNumber("NOTE-" + id).loanId(10L).investorId(investorId)
                .principalAmount(new BigDecimal("1000.00")).outstandingPrincipal(new BigDecimal("1000.00"))
                .principalRepaid(BigDecimal.ZERO.setScale(2)).interestReceived(BigDecimal.ZERO.setScale(2))
                .status(NoteStatus.ACTIVE).build();
    }

    private RepaymentDistributedEventData.NoteAllocation allocation(
            Long noteId, String investorId, String principal, String interest) {
        return allocation(noteId, investorId, principal, interest, "0.00");
    }

    private RepaymentDistributedEventData.NoteAllocation allocation(
            Long noteId, String investorId, String principal, String interest, String prepaymentFee) {
        return new RepaymentDistributedEventData.NoteAllocation(
                noteId, "NOTE-" + noteId, investorId, principal, interest, prepaymentFee);
    }

    private RepaymentDistributedEventData event(RepaymentDistributedEventData.NoteAllocation... allocations) {
        BigDecimal principal = BigDecimal.ZERO.setScale(2);
        BigDecimal interest = BigDecimal.ZERO.setScale(2);
        BigDecimal prepaymentFee = BigDecimal.ZERO.setScale(2);
        for (var allocation : allocations) {
            principal = principal.add(new BigDecimal(allocation.principalAmount()));
            interest = interest.add(new BigDecimal(allocation.interestAmount()));
            prepaymentFee = prepaymentFee.add(new BigDecimal(allocation.prepaymentFeeAmount()));
        }
        return new RepaymentDistributedEventData(REPAYMENT_ID, "RP-42", "SCHEDULED", null,
                prepaymentFee.toPlainString(), prepaymentFee.toPlainString(), "0.00",
                10L, 20L, 30L, principal.add(interest).add(prepaymentFee).toPlainString(), principal.toPlainString(),
                interest.toPlainString(), "0.00", "0.00", "0", "0", "0", "0", "0", "0", "VND",
                null, null, null, NOW, List.of(allocations));
    }
}
