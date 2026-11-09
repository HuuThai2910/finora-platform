package com.finora.investment.service.orderbook.impl;

import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.orderbook.entity.BookOrder;
import com.finora.investment.domain.orderbook.entity.OrderBook;
import com.finora.investment.domain.orderbook.enums.BookOrderStatus;
import com.finora.investment.domain.orderbook.enums.OrderSide;
import com.finora.investment.dto.response.BookOrderResponse;
import com.finora.investment.dto.response.OrderBookPositionResponse;
import com.finora.investment.dto.response.OrderBookSnapshotResponse.PriceLevel;
import com.finora.investment.dto.response.OrderBookSnapshotResponse.TradeTick;
import com.finora.investment.dto.response.OrderBookSnapshotResponse;
import com.finora.investment.dto.response.OrderBookSummaryResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.BestPriceView;
import com.finora.investment.repository.BookOrderRepository;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.OrderBookRepository;
import com.finora.investment.repository.PriceLevelView;
import com.finora.investment.service.orderbook.OrderBookQueryService;
import com.finora.investment.util.SearchPatterns;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Đọc sổ lệnh: danh sách sổ, ảnh chụp độ sâu, lệnh và vị thế của người đang đăng nhập. Mọi danh
 * sách đều giới hạn số dòng; danh sách sổ gom truy vấn theo trang để không N+1.
 */
@Service
@RequiredArgsConstructor
public class OrderBookQueryServiceImpl implements OrderBookQueryService {

    /** Số mức giá mỗi phía và số giao dịch gần nhất trong một ảnh chụp. */
    static final int DEPTH = 20;
    static final int RECENT_TRADES = 20;

    private static final Set<BookOrderStatus> ACTIVE_STATUSES =
            EnumSet.of(BookOrderStatus.PENDING_FUNDS, BookOrderStatus.OPEN, BookOrderStatus.PARTIALLY_FILLED);

    private final OrderBookRepository orderBookRepository;
    private final BookOrderRepository bookOrderRepository;
    private final BookTradeRepository bookTradeRepository;
    private final InvestmentNoteRepository investmentNoteRepository;
    private final MarketListingRepository marketListingRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<OrderBookSummaryResponse> listBooks(String hiddenBorrowerId, Pageable pageable) {
        return listBooks(hiddenBorrowerId, null, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderBookSummaryResponse> listBooks(String hiddenBorrowerId, String q, Pageable pageable) {
        String code = SearchPatterns.containsCode(q);
        Page<Long> listingIds = code != null
                ? investmentNoteRepository.searchTradableListingIds(hiddenBorrowerId, code, pageable)
                : investmentNoteRepository.findTradableListingIds(hiddenBorrowerId, pageable);
        if (listingIds.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, listingIds.getTotalElements());
        }
        List<Long> ids = listingIds.getContent();

        Map<Long, MarketListing> listings = marketListingRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(MarketListing::getId, Function.identity()));
        Map<Long, OrderBook> books = orderBookRepository.findByListingIdIn(ids).stream()
                .collect(Collectors.toMap(OrderBook::getListingId, Function.identity()));
        Map<Long, BestPrices> bestByBook = bestPrices(books.values().stream().map(OrderBook::getId).toList());
        Set<Long> defaulted = new HashSet<>(investmentNoteRepository.findDefaultedListingIds(ids));

        List<OrderBookSummaryResponse> rows = ids.stream()
                .filter(listings::containsKey)
                .map(id -> {
                    MarketListing listing = listings.get(id);
                    Optional<OrderBook> book = Optional.ofNullable(books.get(id));
                    BestPrices best = book.map(b -> bestByBook.get(b.getId())).orElse(BestPrices.NONE);
                    return new OrderBookSummaryResponse(
                            id,
                            listing.getLoanId(),
                            listing.getCreditGrade(),
                            listing.getAnnualInterestRate().toPlainString(),
                            listing.getTermMonths(),
                            listing.getNoteDenomination().toPlainString(),
                            defaulted.contains(id),
                            percentOrNull(best.bestBid()),
                            percentOrNull(best.bestAsk()),
                            book.map(OrderBook::getLastTradePermille).map(OrderBookQueryServiceImpl::percentOrNull).orElse(null),
                            book.map(OrderBook::getLastTradeAt).orElse(null),
                            listing.getApplicationCode(),
                            listing.getContractCode(),
                            listing.getPurpose(),
                            listing.getPurposeCode());
                })
                .toList();
        return new PageImpl<>(rows, pageable, listingIds.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderBookSnapshotResponse snapshot(Long listingId) {
        MarketListing listing = marketListingRepository.findById(listingId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy khoản vay"));
        boolean defaulted = investmentNoteRepository.existsByListingIdAndStatus(listingId, NoteStatus.DEFAULTED);
        BigDecimal referenceOutstanding = investmentNoteRepository.findMaxTradableOutstanding(listingId);
        Optional<OrderBook> book = orderBookRepository.findByListingId(listingId);

        PageRequest depth = PageRequest.of(0, DEPTH);
        List<PriceLevel> bids = book.map(b -> toLevels(bookOrderRepository.findBidLevels(b.getId(), depth))).orElse(List.of());
        List<PriceLevel> asks = book.map(b -> toLevels(bookOrderRepository.findAskLevels(b.getId(), depth))).orElse(List.of());
        List<TradeTick> trades = book
                .map(orderBook -> bookTradeRepository.findByOrderBookIdOrderByExecutedAtDescIdDesc(
                                orderBook.getId(), PageRequest.of(0, RECENT_TRADES)).stream()
                        .map(trade -> new TradeTick(
                                OrderBookPricing.toPercent(trade.getPricePermille()),
                                trade.getQuantity(),
                                trade.getAggressorSide().name(),
                                trade.getExecutedAt()))
                        .toList())
                .orElse(List.of());

        return new OrderBookSnapshotResponse(
                listingId,
                listing.getLoanId(),
                book.map(OrderBook::getLastSequence).orElse(0L),
                listing.getCreditGrade(),
                listing.getAnnualInterestRate().toPlainString(),
                listing.getTermMonths(),
                listing.getNoteDenomination().toPlainString(),
                referenceOutstanding == null ? null : referenceOutstanding.toPlainString(),
                defaulted,
                defaulted ? "Khoản vay gốc đang trong tình trạng nợ xấu; phần vốn thuộc khoản nợ xấu có thể không thu hồi đủ gốc lãi" : null,
                bids.isEmpty() ? null : bids.get(0).pricePercent(),
                asks.isEmpty() ? null : asks.get(0).pricePercent(),
                book.map(OrderBook::getLastTradePermille).map(OrderBookQueryServiceImpl::percentOrNull).orElse(null),
                book.map(OrderBook::getLastTradeAt).orElse(null),
                bids,
                asks,
                trades,
                listing.getPurpose(),
                listing.getPurposeCode());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BookOrderResponse> myOrders(String investorId, boolean activeOnly, Pageable pageable) {
        Page<BookOrder> page = activeOnly
                ? bookOrderRepository.findByInvestorIdAndStatusInOrderByCreatedAtDesc(investorId, ACTIVE_STATUSES, pageable)
                : bookOrderRepository.findByInvestorIdOrderByCreatedAtDesc(investorId, pageable);
        // Khoản vay gốc của cả trang trong một truy vấn, không mỗi lệnh một lần.
        Map<Long, MarketListing> listings = marketListingRepository
                .findAllById(page.getContent().stream().map(BookOrder::getListingId).distinct().toList())
                .stream().collect(Collectors.toMap(MarketListing::getId, Function.identity()));
        return page.map(order -> OrderBookMapper.toResponse(order, listings.get(order.getListingId())));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderBookPositionResponse myPosition(Long listingId, String investorId) {
        MarketListing listing = marketListingRepository.findById(listingId)
                .orElseThrow(() -> InvestmentDomainException.notFound("LISTING_NOT_FOUND", "Không tìm thấy khoản vay"));
        // Số lệnh còn hiệu lực của một người trên một sổ là nhỏ (mỗi lệnh bán khóa ít nhất một
        // Note họ sở hữu), nên không phân trang ở đây.
        List<BookOrderResponse> active = bookOrderRepository
                .findByListingIdAndInvestorIdAndStatusInOrderByCreatedAtDesc(listingId, investorId, ACTIVE_STATUSES)
                .stream().map(order -> OrderBookMapper.toResponse(order, listing)).toList();
        return new OrderBookPositionResponse(
                listingId,
                investmentNoteRepository.countFreeNotes(listingId, investorId),
                investmentNoteRepository.countLockedNotes(listingId, investorId),
                active);
    }

    private Map<Long, BestPrices> bestPrices(List<Long> bookIds) {
        Map<Long, BestPrices> result = new HashMap<>();
        if (bookIds.isEmpty()) {
            return result;
        }
        for (BestPriceView view : bookOrderRepository.findBestPrices(bookIds)) {
            BestPrices current = result.getOrDefault(view.orderBookId(), BestPrices.NONE);
            result.put(view.orderBookId(), view.side() == OrderSide.BID
                    ? new BestPrices(view.maxPermille(), current.bestAsk())
                    : new BestPrices(current.bestBid(), view.minPermille()));
        }
        return result;
    }

    private static List<PriceLevel> toLevels(List<PriceLevelView> views) {
        return views.stream()
                .map(v -> new PriceLevel(OrderBookPricing.toPercent(v.pricePermille()), v.quantity(), v.orderCount()))
                .toList();
    }

    private static String percentOrNull(Integer permille) {
        return permille == null ? null : OrderBookPricing.toPercent(permille);
    }

    private record BestPrices(Integer bestBid, Integer bestAsk) {
        static final BestPrices NONE = new BestPrices(null, null);
    }
}
