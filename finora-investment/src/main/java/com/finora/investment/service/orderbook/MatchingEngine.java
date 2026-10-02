package com.finora.investment.service.orderbook;

import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.investment.domain.orderbook.BookOrder;
import com.finora.investment.domain.orderbook.BookTrade;
import com.finora.investment.domain.orderbook.CancelReason;
import com.finora.investment.domain.orderbook.OrderBook;
import com.finora.investment.domain.orderbook.OrderSide;
import com.finora.investment.domain.orderbook.SettlementStatus;
import com.finora.investment.domain.secondary.NoteTransfer;
import com.finora.investment.repository.BookOrderRepository;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.NoteLockRepository;
import com.finora.investment.repository.NoteTransferRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Khớp một lệnh vừa vào sổ với các lệnh đối ứng đang nằm chờ, rồi thực hiện từng lần khớp.
 *
 * <p>Chạy <strong>bên trong</strong> transaction của người gọi, khi sổ đã bị khóa
 * ({@code Propagation.MANDATORY} để không ai gọi nhầm ngoài transaction). Không có lời gọi mạng
 * nào ở đây: tiền của lệnh mua đã giữ sẵn và Note của lệnh bán đã khóa sẵn, nên khớp chỉ là ghi
 * database. Thanh toán thật chạy sau, ở {@link OrderBookSettlementService}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingEngine {

    /** Số lệnh đối ứng đọc mỗi lượt; đủ lớn để thường chỉ cần một lượt, đủ nhỏ để không đọc vô hạn. */
    private static final int RESTING_BATCH = 200;

    private final BookOrderRepository orderRepository;
    private final BookTradeRepository tradeRepository;
    private final InvestmentNoteRepository noteRepository;
    private final NoteLockRepository lockRepository;
    private final NoteTransferRepository transferRepository;

    /**
     * Khớp {@code incoming} tới khi hết số lượng, hết lệnh đối ứng khớp được giá, hoặc chạm lệnh
     * của chính người đặt.
     *
     * @return các lần khớp đã thực hiện, theo thứ tự
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<BookTrade> match(OrderBook book, BookOrder incoming, Instant now) {
        List<BookTrade> trades = new ArrayList<>();

        while (incoming.getStatus().isResting() && incoming.remainingQuantity() > 0) {
            List<BookOrder> crossing = findCrossing(book, incoming);
            if (crossing.isEmpty()) {
                break;
            }

            OrderMatcher.MatchPlan plan = OrderMatcher.plan(incoming, crossing);
            boolean progressed = false;
            for (OrderMatcher.Fill fill : plan.fills()) {
                BookTrade trade = execute(book, incoming, fill.resting(), fill.quantity(), now);
                if (trade != null) {
                    trades.add(trade);
                    progressed = true;
                }
            }

            if (plan.selfTradeStopped()) {
                cancelRemainder(incoming, CancelReason.SELF_TRADE_PREVENTED, now);
                log.info("Chặn tự khớp, huỷ phần còn lại: orderReference={}", incoming.getOrderReference());
                break;
            }
            // Không khớp được lần nào (mọi lệnh bán trong lượt đều mất Note và đã bị huỷ) thì đọc
            // lại; lệnh bị huỷ không còn trong kết quả nên vòng lặp luôn tiến tới.
            if (!progressed && plan.fills().isEmpty()) {
                break;
            }
        }
        return trades;
    }

    private List<BookOrder> findCrossing(OrderBook book, BookOrder incoming) {
        PageRequest page = PageRequest.of(0, Math.min(incoming.remainingQuantity(), RESTING_BATCH));
        return incoming.getSide() == OrderSide.BID
                ? orderRepository.findCrossingAsks(book.getId(), incoming.getPricePermille(), page)
                : orderRepository.findCrossingBids(book.getId(), incoming.getPricePermille(), page);
    }

    /**
     * Thực hiện một lần khớp: chuyển chủ Note, ghi giao dịch và lịch sử, cập nhật hai lệnh.
     *
     * @return giao dịch đã ghi, hoặc null nếu lệnh bán không còn giao được Note (đã bị huỷ)
     */
    private BookTrade execute(OrderBook book, BookOrder incoming, BookOrder resting, int quantity, Instant now) {
        BookOrder bid = incoming.getSide() == OrderSide.BID ? incoming : resting;
        BookOrder ask = incoming.getSide() == OrderSide.ASK ? incoming : resting;
        int price = resting.getPricePermille();

        List<InvestmentNote> notes = noteRepository.findLockedByAskOrder(ask.getId(), PageRequest.of(0, quantity));
        if (!deliverable(notes, ask, quantity)) {
            // Note đã tất toán hoặc đổi chủ bằng đường khác trong lúc nằm trong lệnh bán: lệnh bán
            // không còn gì để giao. Huỷ nó thay vì để người mua trả tiền mà không nhận được Note.
            cancelRemainder(ask, CancelReason.NOTE_UNAVAILABLE, now);
            log.warn("Huỷ lệnh bán vì Note không còn giao được: orderReference={}", ask.getOrderReference());
            return null;
        }

        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal outstandingTotal = BigDecimal.ZERO;
        boolean defaulted = false;
        List<BigDecimal> noteAmounts = new ArrayList<>(notes.size());
        for (InvestmentNote note : notes) {
            BigDecimal noteAmount = OrderBookPricing.amountForNote(note.getOutstandingPrincipal(), price);
            noteAmounts.add(noteAmount);
            amount = amount.add(noteAmount);
            // Phí tính trên từng Note rồi cộng lại, để mỗi dòng lịch sử chuyển nhượng tự cân
            // (tiền người bán + phí = giá) và tổng của chúng khớp đúng dòng giao dịch.
            fee = fee.add(OrderBookPricing.platformFee(noteAmount));
            outstandingTotal = outstandingTotal.add(note.getOutstandingPrincipal());
            defaulted = defaulted || note.getStatus() == NoteStatus.DEFAULTED;
        }

        BookTrade trade = tradeRepository.save(BookTrade.builder()
                .tradeReference(newTradeReference())
                .orderBookId(book.getId())
                .listingId(book.getListingId())
                .bidOrderId(bid.getId())
                .askOrderId(ask.getId())
                .buyerId(bid.getInvestorId())
                .sellerId(ask.getInvestorId())
                .aggressorSide(incoming.getSide())
                .pricePermille(price)
                .quantity(quantity)
                .amount(amount)
                .platformFee(fee)
                .sellerProceeds(amount.subtract(fee))
                .outstandingTotal(outstandingTotal)
                .defaulted(defaulted)
                .settlementStatus(SettlementStatus.PENDING)
                .settlementAttempts(0)
                .executedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());

        List<Long> noteIds = new ArrayList<>(notes.size());
        List<NoteTransfer> transfers = new ArrayList<>(notes.size());
        for (int i = 0; i < notes.size(); i++) {
            InvestmentNote note = notes.get(i);
            BigDecimal noteAmount = noteAmounts.get(i);
            BigDecimal noteFee = OrderBookPricing.platformFee(noteAmount);
            transfers.add(NoteTransfer.builder()
                    .noteId(note.getId())
                    .tradeId(trade.getId())
                    .sellerId(ask.getInvestorId())
                    .buyerId(bid.getInvestorId())
                    .price(noteAmount)
                    .platformFee(noteFee)
                    .sellerProceeds(noteAmount.subtract(noteFee))
                    .outstandingAtTransfer(note.getOutstandingPrincipal())
                    .defaultedAtTransfer(note.getStatus() == NoteStatus.DEFAULTED)
                    .paymentReference(trade.getTradeReference())
                    .transferredAt(now)
                    .createdBy(bid.getInvestorId())
                    .createdAt(now)
                    .build());

            // Đổi chủ ngay khi khớp: tiền người mua đã giữ đủ nên thanh toán không thể thiếu, chỉ
            // có thể chậm. Người vay không bị ảnh hưởng — chỉ đích đến của gốc lãi đổi.
            note.setInvestorId(bid.getInvestorId());
            note.setUpdatedBy(bid.getInvestorId());
            note.setUpdatedAt(now);
            noteIds.add(note.getId());
        }
        transferRepository.saveAll(transfers);
        lockRepository.deleteByNoteIdIn(noteIds);

        bid.fill(quantity, amount, now);
        ask.fill(quantity, BigDecimal.ZERO, now);
        book.recordTrade(price, now);

        log.info("Khớp lệnh: tradeReference={}, listingId={}, bidOrder={}, askOrder={}, quantity={}, pricePermille={}",
                trade.getTradeReference(), book.getListingId(), bid.getOrderReference(),
                ask.getOrderReference(), quantity, price);
        return trade;
    }

    private boolean deliverable(List<InvestmentNote> notes, BookOrder ask, int quantity) {
        if (notes.size() < quantity) {
            return false;
        }
        for (InvestmentNote note : notes) {
            if (note.getStatus() == NoteStatus.CLOSED || !note.getInvestorId().equals(ask.getInvestorId())) {
                return false;
            }
        }
        return true;
    }

    /** Huỷ phần chưa khớp của một lệnh do hệ thống quyết định; lệnh bán thì mở khóa Note còn lại. */
    void cancelRemainder(BookOrder order, CancelReason reason, Instant now) {
        order.cancel(reason, "SYSTEM", now);
        if (order.getSide() == OrderSide.ASK) {
            lockRepository.deleteByAskOrderId(order.getId());
        }
    }

    private static String newTradeReference() {
        return "TRD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }
}
