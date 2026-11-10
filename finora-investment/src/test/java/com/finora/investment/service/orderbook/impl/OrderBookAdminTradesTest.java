package com.finora.investment.service.orderbook.impl;

import com.finora.common.enums.investment.NoteStatus;
import com.finora.common.exception.BusinessException;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.note.entity.InvestmentNote;
import com.finora.investment.domain.orderbook.entity.BookTrade;
import com.finora.investment.domain.orderbook.enums.OrderSide;
import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import com.finora.investment.domain.orderbook.enums.TradeProgress;
import com.finora.investment.domain.secondary.entity.NoteTransfer;
import com.finora.investment.domain.transferdocument.enums.NoteTransferDocumentStatus;
import com.finora.investment.dto.request.AdminTradeFilter;
import com.finora.investment.dto.response.AdminNoteOwnershipResponse;
import com.finora.investment.dto.response.AdminTradeResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.BookOrderRepository;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.NoteAskPriceView;
import com.finora.investment.repository.NoteLockRepository;
import com.finora.investment.repository.NoteTransferDocumentRepository;
import com.finora.investment.repository.NoteTransferRepository;
import com.finora.investment.repository.TradeDocumentView;
import com.finora.investment.service.BorrowerNameLookup;
import com.finora.investment.service.PartyNameSearch;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lần khớp cho Chợ chuyển nhượng quản trị: chặng gộp từ thanh toán + giấy xác nhận, họ tên hai bên, khoản vay, dư nợ
 * mỗi phần vốn; lọc theo tên hai bên; phần vốn của lần khớp kèm giá đang rao bán.
 */
class OrderBookAdminTradesTest {

    private final BookTradeRepository trades = mock(BookTradeRepository.class);
    private final MarketListingRepository listings = mock(MarketListingRepository.class);
    private final NoteTransferRepository transfers = mock(NoteTransferRepository.class);
    private final InvestmentNoteRepository notes = mock(InvestmentNoteRepository.class);
    private final NoteLockRepository locks = mock(NoteLockRepository.class);
    private final NoteTransferDocumentRepository documents = mock(NoteTransferDocumentRepository.class);
    private final BorrowerNameLookup names = mock(BorrowerNameLookup.class);
    private final PartyNameSearch partyNameSearch = mock(PartyNameSearch.class);

    private final OrderBookAdminServiceImpl service = new OrderBookAdminServiceImpl(trades,
            mock(BookOrderRepository.class), listings, transfers, notes, locks, documents, names, partyNameSearch,
            Clock.fixed(Instant.parse("2026-11-10T02:30:00Z"), ZoneOffset.UTC));

    @Test
    @SuppressWarnings("unchecked")
    void tradesCarryProgressNamesLoanAndOutstandingPerNote() {
        BookTrade settling = trade(1L, "TRD-A", SettlementStatus.PENDING);
        BookTrade drafting = trade(2L, "TRD-B", SettlementStatus.SETTLED);
        BookTrade done = trade(3L, "TRD-C", SettlementStatus.SETTLED);
        BookTrade failedDocument = trade(4L, "TRD-D", SettlementStatus.SETTLED);
        when(trades.findAll(any(Specification.class), any(Pageable.class))).thenReturn(
                new PageImpl<>(List.of(settling, drafting, done, failedDocument), PageRequest.of(0, 20), 4));
        when(listings.findAllById(List.of(28L))).thenReturn(List.of(MarketListing.builder().id(28L).loanId(128L)
                .applicationCode("HS-20261017-AAAA").purpose("Thiết bị phòng khám nha").purposeCode("MEDICAL").build()));
        when(documents.findViewsByTradeReferences(List.of("TRD-A", "TRD-B", "TRD-C", "TRD-D"))).thenReturn(List.of(
                new TradeDocumentView("TRD-B", "CN-TRD-B", NoteTransferDocumentStatus.PENDING, null),
                new TradeDocumentView("TRD-C", "CN-TRD-C", NoteTransferDocumentStatus.ISSUED,
                        Instant.parse("2026-11-08T03:03:00Z")),
                new TradeDocumentView("TRD-D", "CN-TRD-D", NoteTransferDocumentStatus.FAILED, null)));
        when(names.fullNamesOf(Set.of("buyer", "seller"))).thenReturn(Map.of("buyer", "Lý Tuấn Kiệt",
                "seller", "Dương Thị Hồng"));

        Page<AdminTradeResponse> page = service.searchTrades(new AdminTradeFilter(28L, null, null, null, null, null,
                null, null, null), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(AdminTradeResponse::progress)
                .containsExactly("SETTLING", "DOC_PENDING", "DONE", "DOC_PENDING");
        AdminTradeResponse first = page.getContent().get(2);
        assertThat(first.buyerName()).isEqualTo("Lý Tuấn Kiệt");
        assertThat(first.sellerName()).isEqualTo("Dương Thị Hồng");
        assertThat(first.applicationCode()).isEqualTo("HS-20261017-AAAA");
        assertThat(first.purpose()).isEqualTo("Thiết bị phòng khám nha");
        assertThat(first.outstandingPerNote()).isEqualTo("790000.00");
        assertThat(first.pricePercent()).isEqualTo("100.5");
        assertThat(first.documentNumber()).isEqualTo("CN-TRD-C");
        assertThat(first.documentIssuedAt()).isEqualTo(Instant.parse("2026-11-08T03:03:00Z"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void partyNameWithoutMatchesReturnsAnEmptyPageWithoutQuerying() {
        when(partyNameSearch.idsMatching("Không Ai")).thenReturn(Set.of());

        Page<AdminTradeResponse> page = service.searchTrades(new AdminTradeFilter(28L, null, null, null, "Không Ai",
                null, null, null, null), PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        verify(trades, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneLetterPartyIsIgnoredInsteadOfAskingUser() {
        when(trades.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.searchTrades(new AdminTradeFilter(28L, null, TradeProgress.DONE, null, "A", null, null, null, null),
                PageRequest.of(0, 20));

        verify(partyNameSearch, never()).idsMatching(any());
    }

    @Test
    void reversedRangesAreRejected() {
        assertThatThrownBy(() -> service.searchTrades(new AdminTradeFilter(null, null, null, null, null,
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 1), null, null), PageRequest.of(0, 20)))
                .isInstanceOf(InvestmentDomainException.class).extracting("code").isEqualTo("FILTER_RANGE_INVALID");
        assertThatThrownBy(() -> service.searchTrades(new AdminTradeFilter(null, null, null, null, null, null, null,
                new BigDecimal("-1"), null), PageRequest.of(0, 20)))
                .isInstanceOf(InvestmentDomainException.class).extracting("code").isEqualTo("FILTER_VALUE_INVALID");
    }

    @Test
    void singleTradeIsFoundByReferenceOr404() {
        when(trades.findByTradeReference("TRD-C")).thenReturn(Optional.of(trade(3L, "TRD-C", SettlementStatus.SETTLED)));
        when(listings.findAllById(any())).thenReturn(List.of());
        when(documents.findViewsByTradeReferences(any())).thenReturn(List.of());
        when(names.fullNamesOf(any())).thenReturn(Map.of());

        assertThat(service.trade("TRD-C").progress()).isEqualTo("DOC_PENDING");
        assertThatThrownBy(() -> service.trade("TRD-X"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("TRADE_NOT_FOUND");
    }

    @Test
    void tradeNotesTellCurrentHolderNamesAndResalePrice() {
        when(trades.findByTradeReference("TRD-C")).thenReturn(Optional.of(trade(3L, "TRD-C", SettlementStatus.SETTLED)));
        when(transfers.findByTradeIdOrderByNoteIdAsc(3L)).thenReturn(List.of(transfer(10L, 3L, "seller", "buyer")));
        when(notes.findAllById(List.of(10L))).thenReturn(List.of(InvestmentNote.builder().id(10L)
                .noteNumber("NOTE-128-11-0001").listingId(28L).investorId("buyer").status(NoteStatus.ACTIVE)
                .principalAmount(new BigDecimal("1000000.00")).outstandingPrincipal(new BigDecimal("790000.00"))
                .issuedAt(Instant.parse("2026-08-20T02:00:00Z")).build()));
        when(transfers.findByNoteIdOrderByTransferredAtDesc(10L)).thenReturn(List.of(transfer(10L, 3L, "seller",
                "buyer")));
        when(trades.findAllById(List.of(3L))).thenReturn(List.of(trade(3L, "TRD-C", SettlementStatus.SETTLED)));
        when(locks.findAskPrices(List.of(10L))).thenReturn(List.of(new NoteAskPriceView(10L, 1010)));
        when(names.fullNamesOf(Set.of("seller", "buyer"))).thenReturn(Map.of("buyer", "Lý Tuấn Kiệt",
                "seller", "Dương Thị Hồng"));

        List<AdminNoteOwnershipResponse> result = service.tradeNotes("TRD-C");

        assertThat(result).hasSize(1);
        AdminNoteOwnershipResponse note = result.get(0);
        assertThat(note.currentHolderName()).isEqualTo("Lý Tuấn Kiệt");
        assertThat(note.askPricePercent()).isEqualTo("101.0");
        assertThat(note.transfers()).singleElement().satisfies(item -> {
            assertThat(item.tradeReference()).isEqualTo("TRD-C");
            assertThat(item.sellerName()).isEqualTo("Dương Thị Hồng");
            assertThat(item.buyerName()).isEqualTo("Lý Tuấn Kiệt");
        });
    }

    private static BookTrade trade(Long id, String reference, SettlementStatus status) {
        return BookTrade.builder()
                .id(id)
                .tradeReference(reference)
                .orderBookId(280L)
                .listingId(28L)
                .bidOrderId(1L)
                .askOrderId(2L)
                .buyerId("buyer")
                .sellerId("seller")
                .aggressorSide(OrderSide.BID)
                .pricePermille(1005)
                .quantity(12)
                .amount(new BigDecimal("9527400.00"))
                .platformFee(new BigDecimal("476370.00"))
                .sellerProceeds(new BigDecimal("9051030.00"))
                .outstandingTotal(new BigDecimal("9480000.00"))
                .defaulted(false)
                .settlementStatus(status)
                .settlementAttempts(status == SettlementStatus.SETTLED ? 1 : 0)
                .executedAt(Instant.parse("2026-11-08T03:02:00Z"))
                .settledAt(status == SettlementStatus.SETTLED ? Instant.parse("2026-11-08T03:02:42Z") : null)
                .build();
    }

    private static NoteTransfer transfer(Long noteId, Long tradeId, String seller, String buyer) {
        return NoteTransfer.builder().noteId(noteId).tradeId(tradeId).sellerId(seller).buyerId(buyer)
                .price(new BigDecimal("793950.00")).outstandingAtTransfer(new BigDecimal("790000.00"))
                .transferredAt(Instant.parse("2026-11-08T03:02:00Z")).build();
    }
}
