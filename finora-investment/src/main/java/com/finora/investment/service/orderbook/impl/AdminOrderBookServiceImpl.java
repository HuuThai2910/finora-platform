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
import com.finora.investment.service.orderbook.AdminOrderBookService;
import com.finora.investment.util.SearchPatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sổ lệnh cho Chợ chuyển nhượng quản trị.
 *
 * <p>Danh sách lọc và sắp xếp trong bộ nhớ trên tập khoản còn phần vốn lưu hành (khoản vay đang trả nợ, quy mô vài trăm
 * dòng): mỗi nguồn một truy vấn gộp cho cả tập (phần vốn, lệnh chờ, sổ, khoản vay), không đọc theo từng dòng. Khi số
 * khoản vay lớn hơn nhiều thì chuyển phần lọc xuống SQL.</p>
 *
 * <p>Không bọc transaction: không có quan hệ lazy, và lời gọi User (họ tên, tra theo tên) không giữ kết nối cơ sở dữ
 * liệu.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderBookServiceImpl implements AdminOrderBookService {

    /**
     * Giá trị thay cho tập người nắm giữ rỗng khi tìm phần vốn theo mã (JPQL không nhận {@code IN ()}); mã nhà đầu tư
     * không bao giờ là chuỗi rỗng.
     */
    private static final List<String> NO_HOLDER = List.of("");

    private final InvestmentNoteRepository investmentNoteRepository;
    private final MarketListingRepository marketListingRepository;
    private final OrderBookRepository orderBookRepository;
    private final BookOrderRepository bookOrderRepository;
    private final BookTradeRepository bookTradeRepository;
    private final NoteTransferRepository noteTransferRepository;
    private final NoteLockRepository noteLockRepository;
    private final NoteTransferDocumentRepository noteTransferDocumentRepository;
    private final InvestmentCommitmentRepository investmentCommitmentRepository;
    private final InvestmentLoanServicingStateRepository servicingStateRepository;
    private final BorrowerNameLookup nameLookup;
    private final PartyNameSearch partyNameSearch;

    @Override
    public Page<AdminOrderBookResponse> books(AdminOrderBookFilter filter, Pageable pageable) {
        AdminOrderBookFilter applied = (filter == null ? AdminOrderBookFilter.NONE : filter).validate();
        Map<Long, TradableListingView> tradable = investmentNoteRepository.summarizeTradableListings().stream()
                .collect(Collectors.toMap(TradableListingView::listingId, Function.identity()));
        if (tradable.isEmpty()) {
            return Page.empty(pageable);
        }
        Map<Long, MarketListing> listings = marketListingRepository.findAllById(tradable.keySet()).stream()
                .collect(Collectors.toMap(MarketListing::getId, Function.identity()));
        Map<Long, OrderBook> books = orderBookRepository.findByListingIdIn(tradable.keySet()).stream()
                .collect(Collectors.toMap(OrderBook::getListingId, Function.identity()));
        Map<Long, Resting> resting = restingByListing();

        String code = SearchPatterns.normalizedCode(applied.q());
        Set<String> borrowerIds = code == null ? Set.of() : borrowersNamed(applied.q());
        List<Row> rows = tradable.values().stream()
                .filter(view -> listings.containsKey(view.listingId()))
                .map(view -> new Row(view, listings.get(view.listingId()), books.get(view.listingId()),
                        resting.getOrDefault(view.listingId(), Resting.NONE)))
                .filter(row -> applied.grades().isEmpty() || applied.grades().contains(row.listing().getCreditGrade()))
                .filter(row -> applied.rateMin() == null
                        || row.listing().getAnnualInterestRate().compareTo(applied.rateMin()) >= 0)
                .filter(row -> applied.rateMax() == null
                        || row.listing().getAnnualInterestRate().compareTo(applied.rateMax()) <= 0)
                .filter(row -> code == null || matchesCode(row.listing(), code)
                        || borrowerIds.contains(row.listing().getBorrowerId()))
                .filter(row -> applied.state() == null || row.state() == applied.state())
                .sorted(comparator(applied.sort()))
                .toList();

        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to).stream().map(AdminOrderBookServiceImpl::toResponse).toList(),
                pageable, rows.size());
    }

    @Override
    public AdminOrderBookDetailResponse book(Long listingId) {
        MarketListing listing = findListing(listingId);
        long issued = investmentNoteRepository.countByListingId(listingId);
        if (issued == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_BOOK_NOT_FOUND",
                    "Khoản vay chưa phát hành phần vốn nên chưa có sổ lệnh");
        }
        BigDecimal perNote = investmentNoteRepository.findMaxTradableOutstanding(listingId);
        InvestmentLoanServicingState servicing = servicingStateRepository
                .findByLoanApplicationIdIn(List.of(listing.getLoanId())).stream().findFirst().orElse(null);
        Map<SettlementStatus, SettlementTotalsView> settlement = bookTradeRepository
                .sumBySettlementStatusForListing(listingId).stream()
                .collect(Collectors.toMap(SettlementTotalsView::status, Function.identity()));
        SettlementTotalsView settled = settlement.get(SettlementStatus.SETTLED);
        long settledCount = settled == null ? 0 : settled.count();
        long completed = noteTransferDocumentRepository.countByListingIdAndStatus(listingId,
                NoteTransferDocumentStatus.ISSUED);
        long settling = Stream.of(SettlementStatus.PENDING, SettlementStatus.FAILED)
                .map(settlement::get).filter(Objects::nonNull).mapToLong(SettlementTotalsView::count).sum();
        String borrowerName = listing.getBorrowerId() == null ? null
                : nameLookup.fullNamesOf(List.of(listing.getBorrowerId())).get(listing.getBorrowerId());

        return new AdminOrderBookDetailResponse(
                listing.getId(),
                listing.getApplicationCode(),
                listing.getContractCode(),
                listing.getPurpose(),
                listing.getPurposeCode(),
                listing.getBorrowerId(),
                borrowerName,
                listing.getCreditGrade(),
                listing.getAnnualInterestRate().toPlainString(),
                listing.getTermMonths(),
                listing.getNoteDenomination().toPlainString(),
                investmentNoteRepository.findFirstIssuedAt(listingId),
                investmentNoteRepository.existsByListingIdAndStatus(listingId, NoteStatus.DEFAULTED),
                issued,
                investmentNoteRepository.countTradableByListing(listingId),
                perNote == null ? null : perNote.toPlainString(),
                servicing == null ? null : servicing.getLoanNumber(),
                servicing == null ? null : servicing.getDaysPastDue(),
                servicing == null ? null : servicing.getMaturityDate(),
                noteTransferRepository.countTransferredNotesByListing(listingId),
                investmentNoteRepository.countHoldersByListing(listingId),
                money(settled == null ? null : settled.platformFee()),
                settling,
                Math.max(0, settledCount - completed),
                completed);
    }

    @Override
    public Page<AdminListingNoteResponse> notes(Long listingId, String q, Pageable pageable) {
        findListing(listingId);
        String code = SearchPatterns.containsCode(q);
        Page<InvestmentNote> page;
        if (code == null) {
            page = investmentNoteRepository.findByListingIdOrderByIdAsc(listingId, pageable);
        } else {
            Set<String> holders = PartyNameSearch.searchable(q) ? namedOrEmpty(q) : Set.of();
            page = investmentNoteRepository.searchByListing(listingId, code,
                    holders.isEmpty() ? NO_HOLDER : holders, pageable);
        }
        List<InvestmentNote> notes = page.getContent();
        if (notes.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, page.getTotalElements());
        }
        List<Long> noteIds = notes.stream().map(InvestmentNote::getId).toList();
        Map<Long, String> originals = investmentCommitmentRepository
                .findAllById(notes.stream().map(InvestmentNote::getCommitmentId).distinct().toList()).stream()
                .collect(Collectors.toMap(InvestmentCommitment::getId, InvestmentCommitment::getInvestorId));
        Map<Long, Long> transfers = noteTransferRepository.countByNoteIds(noteIds).stream()
                .collect(Collectors.toMap(NoteTransferCountView::noteId, NoteTransferCountView::transfers));
        Map<Long, Integer> asks = noteLockRepository.findAskPrices(noteIds).stream()
                .collect(Collectors.toMap(NoteAskPriceView::noteId, NoteAskPriceView::pricePermille, (a, b) -> a));
        Map<String, String> names = nameLookup.fullNamesOf(Stream.concat(
                notes.stream().map(InvestmentNote::getInvestorId), originals.values().stream())
                .collect(Collectors.toSet()));

        List<AdminListingNoteResponse> rows = notes.stream().map(note -> {
            // Phần vốn của dữ liệu cũ thiếu dòng góp vốn: coi người đang giữ là người góp ban đầu nếu chưa đổi chủ.
            long moved = transfers.getOrDefault(note.getId(), 0L);
            String original = originals.getOrDefault(note.getCommitmentId(), moved == 0 ? note.getInvestorId() : null);
            Integer ask = asks.get(note.getId());
            return new AdminListingNoteResponse(
                    note.getId(),
                    note.getNoteNumber(),
                    note.getStatus().name(),
                    note.getInvestorId(),
                    names.get(note.getInvestorId()),
                    original,
                    original == null ? null : names.get(original),
                    moved,
                    ask == null ? null : OrderBookPricing.toPercent(ask));
        }).toList();
        return new PageImpl<>(rows, pageable, page.getTotalElements());
    }

    private MarketListing findListing(Long listingId) {
        return marketListingRepository.findById(listingId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND",
                        "Không tìm thấy khoản vay"));
    }

    /** Lệnh chờ của mọi sổ gộp theo đợt: hai phía trong một bản ghi. */
    private Map<Long, Resting> restingByListing() {
        Map<Long, Resting> result = new HashMap<>();
        for (RestingListingView view : bookOrderRepository.sumRestingByListing()) {
            Resting current = result.getOrDefault(view.listingId(), Resting.NONE);
            long orders = view.orders() == null ? 0 : view.orders();
            long notes = view.notes() == null ? 0 : view.notes();
            result.put(view.listingId(), view.side() == OrderSide.BID
                    ? new Resting(view.maxPermille(), current.bestAsk(), notes, current.askNotes(),
                            current.orders() + orders)
                    : new Resting(current.bestBid(), view.minPermille(), current.bidNotes(), notes,
                            current.orders() + orders));
        }
        return result;
    }

    /**
     * Người vay có họ tên khớp ô tìm. Ô tìm chung cho mã và tên nên User lỗi thì chỉ còn tìm theo mã (ghi log), không
     * làm hỏng cả danh sách.
     */
    private Set<String> borrowersNamed(String q) {
        if (!PartyNameSearch.searchable(q)) {
            return Set.of();
        }
        return namedOrEmpty(q);
    }

    private Set<String> namedOrEmpty(String q) {
        try {
            return partyNameSearch.idsMatching(q);
        } catch (BusinessException exception) {
            log.warn("Bỏ phần tìm theo họ tên do User chưa phản hồi: code={}", exception.getCode());
            return Set.of();
        }
    }

    private static boolean matchesCode(MarketListing listing, String code) {
        return Stream.of(listing.getApplicationCode(), listing.getContractCode(), listing.getApplicationNumber(),
                        listing.getContractNumber())
                .anyMatch(value -> value != null && value.toUpperCase(Locale.ROOT).contains(code));
    }

    private static Comparator<Row> comparator(AdminOrderBookFilter.Sort sort) {
        Comparator<Row> newestListing = Comparator.comparing((Row row) -> row.listing().getId()).reversed();
        Comparator<Row> primary = switch (sort) {
            // Sổ chưa khớp lần nào xếp cuối.
            case RECENT -> Comparator.comparing(Row::lastTradeAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .reversed();
            case DEPTH -> Comparator.comparingLong((Row row) -> row.resting().bidNotes() + row.resting().askNotes())
                    .reversed();
            case RATE -> Comparator.comparing((Row row) -> row.listing().getAnnualInterestRate()).reversed();
        };
        return primary.thenComparing(newestListing);
    }

    private static AdminOrderBookResponse toResponse(Row row) {
        MarketListing listing = row.listing();
        Resting resting = row.resting();
        return new AdminOrderBookResponse(
                listing.getId(),
                listing.getApplicationCode(),
                listing.getContractCode(),
                listing.getPurpose(),
                listing.getPurposeCode(),
                listing.getCreditGrade(),
                listing.getAnnualInterestRate().toPlainString(),
                listing.getTermMonths(),
                row.view().noteCount(),
                money(row.view().outstandingPrincipal()),
                resting.bestBid() == null ? null : OrderBookPricing.toPercent(resting.bestBid()),
                resting.bestAsk() == null ? null : OrderBookPricing.toPercent(resting.bestAsk()),
                resting.bidNotes(),
                resting.askNotes(),
                row.book() == null || row.book().getLastTradePermille() == null ? null
                        : OrderBookPricing.toPercent(row.book().getLastTradePermille()),
                row.lastTradeAt(),
                row.state().name());
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO.setScale(2) : value).toPlainString();
    }

    /** Lệnh chờ của một sổ: giá tốt nhất và số phần vốn mỗi phía, tổng số lệnh. */
    private record Resting(Integer bestBid, Integer bestAsk, long bidNotes, long askNotes, long orders) {
        static final Resting NONE = new Resting(null, null, 0, 0, 0);
    }

    private record Row(TradableListingView view, MarketListing listing, OrderBook book, Resting resting) {

        Instant lastTradeAt() {
            return book == null ? null : book.getLastTradeAt();
        }

        MarketBookState state() {
            boolean defaulted = view.defaultedCount() != null && view.defaultedCount() > 0;
            return MarketBookState.of(defaulted, resting.orders());
        }
    }
}
