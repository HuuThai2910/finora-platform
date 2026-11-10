package com.finora.investment.service.orderbook.impl;

import com.finora.common.enums.investment.NoteStatus;
import com.finora.common.exception.BusinessException;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.note.entity.InvestmentLoanServicingState;
import com.finora.investment.domain.note.entity.InvestmentNote;
import com.finora.investment.domain.order.entity.InvestmentCommitment;
import com.finora.investment.domain.orderbook.entity.OrderBook;
import com.finora.investment.domain.orderbook.enums.MarketBookState;
import com.finora.investment.domain.orderbook.enums.OrderSide;
import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import com.finora.investment.domain.transferdocument.enums.NoteTransferDocumentStatus;
import com.finora.investment.dto.request.AdminOrderBookFilter;
import com.finora.investment.dto.response.AdminListingNoteResponse;
import com.finora.investment.dto.response.AdminOrderBookDetailResponse;
import com.finora.investment.dto.response.AdminOrderBookResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.BookOrderRepository;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.InvestmentLoanServicingStateRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.NoteAskPriceView;
import com.finora.investment.repository.NoteLockRepository;
import com.finora.investment.repository.NoteTransferCountView;
import com.finora.investment.repository.NoteTransferDocumentRepository;
import com.finora.investment.repository.NoteTransferRepository;
import com.finora.investment.repository.OrderBookRepository;
import com.finora.investment.repository.RestingListingView;
import com.finora.investment.repository.SettlementTotalsView;
import com.finora.investment.repository.TradableListingView;
import com.finora.investment.service.BorrowerNameLookup;
import com.finora.investment.service.PartyNameSearch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sổ lệnh của Chợ chuyển nhượng quản trị: lọc, sắp xếp, trạng thái sổ và cắt trang trên tập khoản còn phần vốn; chi
 * tiết một sổ (trả nợ, số liệu chuyển nhượng, đếm lần khớp theo chặng); phần vốn của khoản vay.
 */
class AdminOrderBookServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-10T02:30:00Z");

    private final InvestmentNoteRepository notes = mock(InvestmentNoteRepository.class);
    private final MarketListingRepository listings = mock(MarketListingRepository.class);
    private final OrderBookRepository orderBooks = mock(OrderBookRepository.class);
    private final BookOrderRepository orders = mock(BookOrderRepository.class);
    private final BookTradeRepository trades = mock(BookTradeRepository.class);
    private final NoteTransferRepository transfers = mock(NoteTransferRepository.class);
    private final NoteLockRepository locks = mock(NoteLockRepository.class);
    private final NoteTransferDocumentRepository documents = mock(NoteTransferDocumentRepository.class);
    private final InvestmentCommitmentRepository commitments = mock(InvestmentCommitmentRepository.class);
    private final InvestmentLoanServicingStateRepository servicing = mock(InvestmentLoanServicingStateRepository.class);
    private final BorrowerNameLookup names = mock(BorrowerNameLookup.class);
    private final PartyNameSearch partyNameSearch = mock(PartyNameSearch.class);

    private final AdminOrderBookServiceImpl service = new AdminOrderBookServiceImpl(notes, listings, orderBooks,
            orders, trades, transfers, locks, documents, commitments, servicing, names, partyNameSearch);

    @BeforeEach
    void setUp() {
        // Ba khoản còn phần vốn: 28 có lệnh và khớp gần nhất, 18 có lệnh nhưng lãi thấp, 9 nợ xấu, 7 chưa có lệnh.
        when(notes.summarizeTradableListings()).thenReturn(List.of(
                new TradableListingView(28L, 250L, new BigDecimal("239500000.00"), 0L),
                new TradableListingView(18L, 150L, new BigDecimal("118500000.00"), 0L),
                new TradableListingView(9L, 40L, new BigDecimal("23200000.00"), 40L),
                new TradableListingView(7L, 50L, new BigDecimal("33000000.00"), 0L)));
        when(listings.findAllById(any())).thenReturn(List.of(
                listing(28L, "HS-20261017-AAAA", "B", "13.0000", "u-28"),
                listing(18L, "HS-20260820-BBBB", "A", "11.5000", "u-18"),
                listing(9L, "HS-20260610-CCCC", "D", "19.0000", "u-9"),
                listing(7L, "HS-20260605-DDDD", "A", "12.0000", "u-7")));
        when(orderBooks.findByListingIdIn(any())).thenReturn(List.of(
                book(28L, 1000, "2026-11-10T02:12:00Z"),
                book(18L, 1000, "2026-11-10T01:47:00Z"),
                book(9L, 480, "2026-11-07T07:15:00Z")));
        when(orders.sumRestingByListing()).thenReturn(List.of(
                new RestingListingView(28L, OrderSide.BID, 17L, 125L, 990, 960),
                new RestingListingView(28L, OrderSide.ASK, 10L, 55L, 1020, 1000),
                new RestingListingView(18L, OrderSide.BID, 11L, 64L, 1000, 990),
                new RestingListingView(18L, OrderSide.ASK, 11L, 76L, 1020, 1005),
                new RestingListingView(9L, OrderSide.ASK, 3L, 20L, 600, 550)));
    }

    @Test
    void defaultListIsNewestTradeFirstWithStateAndBestPrices() {
        Page<AdminOrderBookResponse> page = service.books(AdminOrderBookFilter.NONE, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(AdminOrderBookResponse::listingId).containsExactly(28L, 18L, 9L, 7L);
        AdminOrderBookResponse first = page.getContent().get(0);
        assertThat(first.bestBidPercent()).isEqualTo("99.0");
        assertThat(first.bestAskPercent()).isEqualTo("100.0");
        assertThat(first.outstandingNotes()).isEqualTo(250L);
        assertThat(first.outstandingPrincipal()).isEqualTo("239500000.00");
        assertThat(first.lastTradePercent()).isEqualTo("100.0");
        assertThat(page.getContent()).extracting(AdminOrderBookResponse::state)
                .containsExactly("HAS_ORDERS", "HAS_ORDERS", "DEFAULTED", "NO_ORDERS");
        // Sổ chưa có lệnh nào: không có giá, không có lần khớp.
        AdminOrderBookResponse empty = page.getContent().get(3);
        assertThat(empty.bestBidPercent()).isNull();
        assertThat(empty.lastTradeAt()).isNull();
    }

    @Test
    void filtersCombineAndSortByDepthOrRate() {
        AdminOrderBookFilter defaulted = new AdminOrderBookFilter(null, MarketBookState.DEFAULTED, null, null, null,
                null);
        assertThat(service.books(defaulted, PageRequest.of(0, 20)).getContent())
                .extracting(AdminOrderBookResponse::listingId).containsExactly(9L);

        AdminOrderBookFilter gradeA = new AdminOrderBookFilter(null, null, List.of("a"), new BigDecimal("11"),
                new BigDecimal("12"), AdminOrderBookFilter.Sort.RATE);
        assertThat(service.books(gradeA, PageRequest.of(0, 20)).getContent())
                .extracting(AdminOrderBookResponse::listingId).containsExactly(7L, 18L);

        AdminOrderBookFilter depth = new AdminOrderBookFilter(null, null, null, null, null,
                AdminOrderBookFilter.Sort.DEPTH);
        assertThat(service.books(depth, PageRequest.of(0, 20)).getContent())
                .extracting(AdminOrderBookResponse::listingId).containsExactly(28L, 18L, 9L, 7L);
    }

    @Test
    void searchMatchesCodeOrBorrowerName() {
        when(partyNameSearch.idsMatching("Phúc")).thenReturn(Set.of("u-18"));
        assertThat(service.books(new AdminOrderBookFilter("Phúc", null, null, null, null, null), PageRequest.of(0, 20))
                .getContent()).extracting(AdminOrderBookResponse::listingId).containsExactly(18L);

        when(partyNameSearch.idsMatching("hs-20260610")).thenReturn(Set.of());
        assertThat(service.books(new AdminOrderBookFilter("hs-20260610", null, null, null, null, null),
                PageRequest.of(0, 20)).getContent()).extracting(AdminOrderBookResponse::listingId).containsExactly(9L);
    }

    @Test
    void userOutageOnlyDropsTheNamePartOfTheSearch() {
        when(partyNameSearch.idsMatching(any())).thenThrow(
                new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "USER_SEARCH_UNAVAILABLE", "x"));

        assertThat(service.books(new AdminOrderBookFilter("HS-20261017", null, null, null, null, null),
                PageRequest.of(0, 20)).getContent()).extracting(AdminOrderBookResponse::listingId).containsExactly(28L);
    }

    @Test
    void pagesAreCutAfterFilteringAndSorting() {
        Page<AdminOrderBookResponse> second = service.books(AdminOrderBookFilter.NONE, PageRequest.of(1, 3));

        assertThat(second.getTotalElements()).isEqualTo(4);
        assertThat(second.getContent()).extracting(AdminOrderBookResponse::listingId).containsExactly(7L);
    }

    @Test
    void reversedRateRangeIsRejected() {
        assertThatThrownBy(() -> service.books(new AdminOrderBookFilter(null, null, null, new BigDecimal("15"),
                new BigDecimal("10"), null), PageRequest.of(0, 20)))
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("FILTER_RANGE_INVALID");
    }

    @Test
    void detailCombinesNotesServicingTransfersAndTradeStages() {
        MarketListing listing = listing(28L, "HS-20261017-AAAA", "B", "13.0000", "u-28");
        when(listings.findById(28L)).thenReturn(java.util.Optional.of(listing));
        when(notes.countByListingId(28L)).thenReturn(250L);
        when(notes.countTradableByListing(28L)).thenReturn(250L);
        when(notes.findMaxTradableOutstanding(28L)).thenReturn(new BigDecimal("958000.00"));
        when(notes.findFirstIssuedAt(28L)).thenReturn(Instant.parse("2026-10-17T07:01:00Z"));
        when(notes.countHoldersByListing(28L)).thenReturn(9L);
        when(transfers.countTransferredNotesByListing(28L)).thenReturn(30L);
        when(servicing.findByLoanApplicationIdIn(List.of(128L))).thenReturn(List.of(
                InvestmentLoanServicingState.disbursed(128L, "LN-2026-0028", LocalDate.of(2028, 10, 17), NOW)));
        when(trades.sumBySettlementStatusForListing(28L)).thenReturn(List.of(
                new SettlementTotalsView(SettlementStatus.SETTLED, 3L, new BigDecimal("31260000.00"),
                        new BigDecimal("1563000.00")),
                new SettlementTotalsView(SettlementStatus.PENDING, 1L, new BigDecimal("4790000.00"),
                        new BigDecimal("239500.00"))));
        when(documents.countByListingIdAndStatus(28L, NoteTransferDocumentStatus.ISSUED)).thenReturn(2L);
        when(names.fullNamesOf(List.of("u-28"))).thenReturn(Map.of("u-28", "Hồ Minh Tuấn"));

        AdminOrderBookDetailResponse detail = service.book(28L);

        assertThat(detail.borrowerFullName()).isEqualTo("Hồ Minh Tuấn");
        assertThat(detail.loanNumber()).isEqualTo("LN-2026-0028");
        assertThat(detail.daysPastDue()).isZero();
        assertThat(detail.maturityDate()).isEqualTo(LocalDate.of(2028, 10, 17));
        assertThat(detail.outstandingPerNote()).isEqualTo("958000.00");
        assertThat(detail.transferredNotes()).isEqualTo(30L);
        assertThat(detail.holderCount()).isEqualTo(9L);
        assertThat(detail.feeCollected()).isEqualTo("1563000.00");
        assertThat(detail.settlingTrades()).isEqualTo(1L);
        assertThat(detail.documentPendingTrades()).isEqualTo(1L);
        assertThat(detail.completedTrades()).isEqualTo(2L);
    }

    @Test
    void listingWithoutNotesHasNoBook() {
        when(listings.findById(5L)).thenReturn(java.util.Optional.of(listing(5L, "HS-1", "A", "10.0000", "u-5")));
        when(notes.countByListingId(5L)).thenReturn(0L);

        assertThatThrownBy(() -> service.book(5L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ORDER_BOOK_NOT_FOUND");
    }

    @Test
    void notesShowHolderOriginalTransfersAndAskPrice() {
        when(listings.findById(28L)).thenReturn(java.util.Optional.of(
                listing(28L, "HS-20261017-AAAA", "B", "13.0000", "u-28")));
        InvestmentNote moved = note(1L, 11L, "inv-b");
        InvestmentNote kept = note(2L, 12L, "inv-c");
        when(notes.findByListingIdOrderByIdAsc(eq(28L), any())).thenReturn(
                new PageImpl<>(List.of(moved, kept), PageRequest.of(0, 10), 250));
        when(commitments.findAllById(any())).thenReturn(List.of(
                InvestmentCommitment.builder().id(11L).investorId("inv-a").build(),
                InvestmentCommitment.builder().id(12L).investorId("inv-c").build()));
        when(transfers.countByNoteIds(List.of(1L, 2L))).thenReturn(List.of(new NoteTransferCountView(1L, 2L)));
        when(locks.findAskPrices(List.of(1L, 2L))).thenReturn(List.of(new NoteAskPriceView(1L, 1010)));
        when(names.fullNamesOf(any())).thenReturn(Map.of("inv-a", "Nguyễn Minh Anh", "inv-b", "Trần Quốc Bảo",
                "inv-c", "Lê Thu Hà"));

        Page<AdminListingNoteResponse> page = service.notes(28L, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(250);
        AdminListingNoteResponse first = page.getContent().get(0);
        assertThat(first.holderName()).isEqualTo("Trần Quốc Bảo");
        assertThat(first.originalInvestorName()).isEqualTo("Nguyễn Minh Anh");
        assertThat(first.transferCount()).isEqualTo(2L);
        assertThat(first.askPricePercent()).isEqualTo("101.0");
        AdminListingNoteResponse second = page.getContent().get(1);
        assertThat(second.transferCount()).isZero();
        assertThat(second.askPricePercent()).isNull();
    }

    @Test
    void noteSearchMatchesNumberOrHolderName() {
        when(listings.findById(28L)).thenReturn(java.util.Optional.of(
                listing(28L, "HS-20261017-AAAA", "B", "13.0000", "u-28")));
        when(partyNameSearch.idsMatching("Bảo")).thenReturn(Set.of("inv-b"));
        when(notes.searchByListing(eq(28L), any(), anyCollection(), any())).thenReturn(Page.empty());

        service.notes(28L, "Bảo", PageRequest.of(0, 10));
        verify(notes).searchByListing(eq(28L), eq("%BẢO%"), eq(Set.of("inv-b")), any());

        // Một ký tự: chỉ tìm theo mã, không hỏi User; tập người nắm giữ thay bằng giá trị không khớp ai.
        service.notes(28L, "7", PageRequest.of(0, 10));
        verify(partyNameSearch, never()).idsMatching("7");
        verify(notes).searchByListing(eq(28L), eq("%7%"), eq(List.of("")), any());
    }

    private static MarketListing listing(Long id, String code, String grade, String rate, String borrowerId) {
        return MarketListing.builder()
                .id(id)
                .loanId(100L + id)
                .applicationNumber("LA-" + id)
                .applicationCode(code)
                .contractCode(code.replace("HS-", "HĐ-"))
                .contractNumber("LC-" + id)
                .borrowerId(borrowerId)
                .purpose("Mục đích " + id)
                .purposeCode("SMALL_BUSINESS")
                .creditGrade(grade)
                .annualInterestRate(new BigDecimal(rate))
                .termMonths(24)
                .noteDenomination(new BigDecimal("1000000.00"))
                .build();
    }

    private static OrderBook book(Long listingId, int lastPermille, String lastAt) {
        return OrderBook.builder().id(listingId * 10).listingId(listingId).loanId(100L + listingId)
                .lastSequence(1L).lastTradePermille(lastPermille).lastTradeAt(Instant.parse(lastAt)).build();
    }

    private static InvestmentNote note(Long id, Long commitmentId, String holder) {
        return InvestmentNote.builder().id(id).noteNumber("NOTE-128-" + commitmentId + "-000" + id)
                .commitmentId(commitmentId).listingId(28L).loanId(128L).investorId(holder)
                .status(NoteStatus.ACTIVE).build();
    }
}
