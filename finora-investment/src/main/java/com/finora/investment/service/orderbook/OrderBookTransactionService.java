package com.finora.investment.service.orderbook;

import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.investment.domain.orderbook.BookOrder;
import com.finora.investment.domain.orderbook.BookOrderStatus;
import com.finora.investment.domain.orderbook.CancelReason;
import com.finora.investment.domain.orderbook.NoteLock;
import com.finora.investment.domain.orderbook.OrderBook;
import com.finora.investment.domain.orderbook.OrderSide;
import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.BookOrderRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.NoteLockRepository;
import com.finora.investment.repository.OrderBookRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Các transaction ngắn của sổ lệnh. Mỗi method public là một transaction, không chờ mạng bên trong.
 *
 * <p>Thứ tự khóa luôn là <b>sổ trước</b>, rồi mới tới lệnh và Note. Mọi đường ghi vào sổ đều đi qua
 * khóa này nên không có hai transaction giữ khóa theo thứ tự ngược nhau — không có deadlock.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBookTransactionService {

    private final OrderBookRepository bookRepository;
    private final BookOrderRepository orderRepository;
    private final InvestmentNoteRepository noteRepository;
    private final NoteLockRepository lockRepository;
    private final MarketListingRepository listingRepository;
    private final MatchingEngine matchingEngine;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;

    /** Nội dung lệnh đã kiểm tra hình thức, chuyển từ request vào tầng transaction. */
    public record OrderCommand(
            Long listingId,
            String investorId,
            OrderSide side,
            int pricePermille,
            int quantity,
            boolean acknowledgedDefault,
            String idempotencyKey,
            String requestHash) {
    }

    /**
     * Bước 1 của lệnh mua: ghi lệnh {@code PENDING_FUNDS} kèm số tiền cần giữ, chưa vào sổ.
     *
     * <p>Không khóa sổ: lệnh chưa vào sổ thì chưa ảnh hưởng ai. Dư nợ đọc ở đây chỉ để tính số
     * tiền giữ — là trần, không phải số tiền thật của lần khớp.</p>
     */
    @Transactional
    public BookOrder createPendingBid(OrderCommand command) {
        ensureBook(command.listingId());
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

    /**
     * Bước 3 của lệnh mua: tiền đã giữ, khóa sổ, cho lệnh vào sổ và khớp.
     *
     * <p>Idempotent: gọi lại khi lệnh đã vào sổ thì trả trạng thái hiện tại.</p>
     */
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

    @Transactional
    public BookOrder rejectBid(Long orderId, String reasonCode, String reasonDetail) {
        BookOrder order = orderRepository.findById(orderId).orElseThrow();
        if (order.getStatus() == BookOrderStatus.PENDING_FUNDS) {
            order.reject(reasonCode, reasonDetail, clock.instant());
        }
        return order;
    }

    /**
     * Đặt lệnh bán: khóa sổ, chọn và khóa Note, vào sổ, khớp — trong một transaction vì không có
     * bước nào phải chờ mạng.
     */
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

    /**
     * Huỷ phần chưa khớp của một lệnh.
     *
     * <p>Khóa sổ trước rồi mới đọc lại lệnh, để không huỷ nhầm một lệnh vừa khớp hết ở transaction
     * khác. Huỷ lại lệnh đã huỷ trả kết quả cũ — người dùng có thể bấm hai lần.</p>
     */
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

    /**
     * Tạo sổ nếu chưa có. Cố ý không nạp entity sổ ở đây: nếu nạp trước rồi mới khóa, Hibernate trả
     * lại bản đã nạp (có thể cũ) thay vì bản đọc dưới khóa, và bộ đếm {@code last_sequence} bị ghi
     * đè bằng giá trị cũ.
     */
    private void ensureBook(Long listingId) {
        MarketListing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy khoản vay"));
        bookRepository.insertIfAbsent(listingId, listing.getLoanId(), clock.instant());
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
