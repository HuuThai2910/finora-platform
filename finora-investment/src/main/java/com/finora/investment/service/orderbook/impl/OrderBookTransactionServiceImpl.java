package com.finora.investment.service.orderbook.impl;

import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.investment.domain.orderbook.BookOrder;
import com.finora.investment.domain.orderbook.BookOrderStatus;
import com.finora.investment.domain.orderbook.CancelReason;
import com.finora.investment.domain.orderbook.NoteLock;
import com.finora.investment.domain.orderbook.OrderBook;
import com.finora.investment.domain.orderbook.OrderSide;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.BookOrderRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.NoteLockRepository;
import com.finora.investment.repository.OrderBookRepository;
import com.finora.investment.service.InvestorEligibility;
import com.finora.investment.service.orderbook.MatchingEngine;
import com.finora.investment.service.orderbook.OrderBookChangedEvent;
import com.finora.investment.service.orderbook.OrderBookPricing;
import com.finora.investment.service.orderbook.OrderBookTransactionService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Các transaction ngắn của sổ lệnh. Mỗi method public là một transaction, không chờ mạng bên trong.
 *
 * <p>Thứ tự khóa luôn là <b>sổ trước</b>, rồi mới tới lệnh và Note. Mọi đường ghi vào sổ đều đi qua
 * khóa này nên không có hai transaction giữ khóa theo thứ tự ngược nhau — không có deadlock.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBookTransactionServiceImpl implements OrderBookTransactionService {

    private final OrderBookRepository bookRepository;
    private final BookOrderRepository orderRepository;
    private final InvestmentNoteRepository noteRepository;
    private final NoteLockRepository lockRepository;
    private final MarketListingRepository listingRepository;
    private final MatchingEngine matchingEngine;
    private final InvestorEligibility eligibility;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;

    @Override
    @Transactional
    public BookOrder createPendingBid(OrderCommand command) {
        MarketListing listing = ensureBook(command.listingId());
        // Người vay mua Note khoản vay của chính mình cũng là tự cấp vốn. Lệnh bán không cần chặn:
        // người vay không thể có Note của khoản mình.
        eligibility.requireNotBorrower(listing, command.investorId());
        OrderBook book = bookRepository.findByListingId(command.listingId()).orElseThrow();
        requireDefaultAcknowledged(command);

        BigDecimal maxOutstanding = noteRepository.findMaxTradableOutstanding(command.listingId());
        if (maxOutstanding == null || maxOutstanding.signum() <= 0) {
            throw InvestmentDomainException.conflict(
                    "BOOK_NOT_TRADABLE", "Khoản vay này chưa có Note nào đang lưu hành để mua");
        }

        Instant now = clock.instant();
        BookOrder order = BookOrder.builder()
                .orderReference(newOrderReference())
                .orderBookId(book.getId())
                .listingId(command.listingId())
                .investorId(command.investorId())
                .side(OrderSide.BID)
                .pricePermille(command.pricePermille())
                .quantity(command.quantity())
                .filledQuantity(0)
                .status(BookOrderStatus.PENDING_FUNDS)
                .holdAmount(OrderBookPricing.holdAmount(maxOutstanding, command.pricePermille(), command.quantity()))
                .holdConsumed(BigDecimal.ZERO)
                .acknowledgedDefault(command.acknowledgedDefault())
                .idempotencyKey(command.idempotencyKey())
                .requestHash(command.requestHash())
                .createdBy(command.investorId())
                .updatedBy(command.investorId())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return orderRepository.save(order);
    }

    @Override
    @Transactional
    public BookOrder activateBid(Long orderId, Long listingId, String holdReference) {
        OrderBook book = lockBook(listingId);
        BookOrder order = orderRepository.findById(orderId).orElseThrow();
        if (order.getStatus() != BookOrderStatus.PENDING_FUNDS) {
            return order;
        }
        Instant now = clock.instant();
        order.activate(holdReference, book.nextSequence(now), now);
        matchingEngine.match(book, order, now);
        publishChanged(book);
        return order;
    }

    @Override
    @Transactional
    public BookOrder rejectBid(Long orderId, String reasonCode, String reasonDetail) {
        BookOrder order = orderRepository.findById(orderId).orElseThrow();
        if (order.getStatus() == BookOrderStatus.PENDING_FUNDS) {
            order.reject(reasonCode, reasonDetail, clock.instant());
        }
        return order;
    }

    @Override
    @Transactional
    public BookOrder placeAsk(OrderCommand command) {
        ensureBook(command.listingId());
        OrderBook book = lockBook(command.listingId());
        requireDefaultAcknowledged(command);

        // Chọn Note dưới khóa sổ: mọi việc khóa/mở khóa Note đều đi qua khóa này, nên số Note rảnh
        // đọc được ở đây không thể bị lệnh bán khác lấy mất trước khi ghi khóa.
        List<InvestmentNote> notes = noteRepository.findFreeNotes(
                command.listingId(), command.investorId(), PageRequest.of(0, command.quantity()));
        if (notes.size() < command.quantity()) {
            throw InvestmentDomainException.conflict(
                    "INSUFFICIENT_FREE_NOTES",
                    "Bạn chỉ còn " + notes.size() + " Note của khoản vay này chưa nằm trong lệnh bán nào");
        }

        Instant now = clock.instant();
        BookOrder order = orderRepository.save(BookOrder.builder()
                .orderReference(newOrderReference())
                .orderBookId(book.getId())
                .listingId(command.listingId())
                .investorId(command.investorId())
                .side(OrderSide.ASK)
                .pricePermille(command.pricePermille())
                .quantity(command.quantity())
                .filledQuantity(0)
                .status(BookOrderStatus.OPEN)
                .sequence(book.nextSequence(now))
                .holdConsumed(BigDecimal.ZERO)
                .acknowledgedDefault(command.acknowledgedDefault())
                .idempotencyKey(command.idempotencyKey())
                .requestHash(command.requestHash())
                .createdBy(command.investorId())
                .updatedBy(command.investorId())
                .createdAt(now)
                .updatedAt(now)
                .build());

        lockRepository.saveAll(notes.stream()
                .map(note -> NoteLock.builder()
                        .noteId(note.getId())
                        .askOrderId(order.getId())
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .toList());

        matchingEngine.match(book, order, now);
        publishChanged(book);
        return order;
    }

    @Override
    @Transactional
    public BookOrder cancel(String orderReference, String investorId) {
        BookOrder order = orderRepository.findByOrderReference(orderReference)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "ORDER_NOT_FOUND", "Không tìm thấy lệnh này"));
        if (!order.getInvestorId().equals(investorId)) {
            throw InvestmentDomainException.forbidden(
                    "ACCESS_DENIED", "Bạn không có quyền thao tác trên lệnh này");
        }

        OrderBook book = lockBook(order.getListingId());
        entityManager.refresh(order);

        switch (order.getStatus()) {
            case CANCELLED -> {
                return order;
            }
            case FILLED -> throw InvestmentDomainException.conflict(
                    "ORDER_ALREADY_FILLED", "Lệnh đã khớp hết nên không còn gì để huỷ");
            case PENDING_FUNDS -> throw InvestmentDomainException.conflict(
                    "ORDER_NOT_CANCELLABLE", "Lệnh đang chờ giữ tiền, vui lòng thử huỷ lại sau ít giây");
            case REJECTED -> throw InvestmentDomainException.conflict(
                    "ORDER_NOT_CANCELLABLE", "Lệnh đã bị từ chối, không cần huỷ");
            default -> {
                // OPEN hoặc PARTIALLY_FILLED: huỷ bên dưới.
            }
        }

        Instant now = clock.instant();
        order.cancel(CancelReason.USER, investorId, now);
        if (order.getSide() == OrderSide.ASK) {
            lockRepository.deleteByAskOrderId(order.getId());
        }
        // Lệnh mua: phần tiền giữ còn lại do worker nhả sau khi các lần khớp trước đó thanh toán
        // xong (BookOrderRepository#findReleasePending), không nhả ở đây để không chờ mạng.
        book.nextSequence(now);
        publishChanged(book);

        log.info("Huỷ lệnh trên sổ: orderReference={}, filledQuantity={}",
                orderReference, order.getFilledQuantity());
        return order;
    }

    @Override
    @Transactional
    public boolean expirePendingBid(Long orderId, String holdReference) {
        BookOrder order = orderRepository.findById(orderId).orElseThrow();
        lockBook(order.getListingId());
        entityManager.refresh(order);
        if (order.getStatus() != BookOrderStatus.PENDING_FUNDS) {
            return false;
        }
        order.expireUnfunded(holdReference, clock.instant());
        log.warn("Từ chối lệnh mua kẹt chờ giữ tiền: orderReference={}", order.getOrderReference());
        return true;
    }

    @Override
    @Transactional
    public int closeBook(Long listingId) {
        OrderBook book = lockBook(listingId);
        // Đọc lại dưới khóa sổ: giữa lúc quét và lúc khóa có thể đã có Note mới đổi trạng thái.
        if (noteRepository.findMaxTradableOutstanding(listingId) != null) {
            return 0;
        }
        Instant now = clock.instant();
        List<BookOrder> resting = orderRepository.findResting(book.getId());
        for (BookOrder order : resting) {
            order.cancel(CancelReason.BOOK_CLOSED, "SYSTEM", now);
            if (order.getSide() == OrderSide.ASK) {
                lockRepository.deleteByAskOrderId(order.getId());
            }
        }
        if (!resting.isEmpty()) {
            book.nextSequence(now);
            publishChanged(book);
            log.info("Đóng sổ lệnh vì khoản vay không còn Note: listingId={}, cancelledOrders={}",
                    listingId, resting.size());
        }
        return resting.size();
    }

    /**
     * Tạo sổ nếu chưa có. Cố ý không nạp entity sổ ở đây: nếu nạp trước rồi mới khóa, Hibernate trả
     * lại bản đã nạp (có thể cũ) thay vì bản đọc dưới khóa, và bộ đếm {@code last_sequence} bị ghi
     * đè bằng giá trị cũ.
     */
    private MarketListing ensureBook(Long listingId) {
        MarketListing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy khoản vay"));
        bookRepository.insertIfAbsent(listingId, listing.getLoanId(), clock.instant());
        return listing;
    }

    private OrderBook lockBook(Long listingId) {
        return bookRepository.findByListingIdForUpdate(listingId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "ORDER_BOOK_NOT_FOUND", "Khoản vay này chưa có sổ lệnh"));
    }

    /**
     * Khoản vay có Note nợ xấu thì người đặt phải xác nhận đã đọc cảnh báo. Kiểm ở backend để cảnh
     * báo không phụ thuộc vào việc giao diện có hiện hay không (INV-E1 mục 8.2).
     */
    private void requireDefaultAcknowledged(OrderCommand command) {
        if (!command.acknowledgedDefault()
                && noteRepository.existsByListingIdAndStatus(command.listingId(), NoteStatus.DEFAULTED)) {
            throw InvestmentDomainException.invalidInput(
                    "DEFAULT_NOT_ACKNOWLEDGED",
                    "Khoản vay này đang nợ xấu; hãy xác nhận đã đọc cảnh báo trước khi đặt lệnh");
        }
    }

    /** Báo sổ đã đổi; stream chỉ đẩy ảnh mới sau khi transaction commit. */
    private void publishChanged(OrderBook book) {
        events.publishEvent(new OrderBookChangedEvent(book.getListingId(), book.getLastSequence()));
    }

    private static String newOrderReference() {
        return "OB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }
}
