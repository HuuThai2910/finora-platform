package com.finora.investment.service.orderbook;

import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentTransferResult;
import com.finora.investment.domain.orderbook.BookOrder;
import com.finora.investment.domain.orderbook.BookTrade;
import com.finora.investment.repository.BookOrderRepository;
import com.finora.investment.repository.BookTradeRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Thanh toán sau khớp: chuyển tiền đã giữ của người mua sang người bán, rồi nhả phần giữ thừa.
 *
 * <p>Tách khỏi lúc khớp vì khớp nằm trong transaction đang khóa sổ, còn gọi Payment là chờ mạng
 * (08-cross-service-flows: không giữ transaction khi chờ mạng). Trạng thái {@code PENDING} trên
 * {@code order_book_trades} đóng vai outbox: ghi cùng transaction với việc khớp, worker đọc và
 * thực hiện sau.</p>
 *
 * <p>Mọi lời gọi Payment idempotent theo mã ({@code tradeReference} cho thanh toán, mã lệnh cho nhả
 * tiền), nên chạy lại sau khi service chết giữa chừng hay hai instance cùng xử lý một dòng đều
 * không chuyển tiền hai lần.</p>
 */
@Slf4j
@Service
public class OrderBookSettlementService {

    private final BookTradeRepository tradeRepository;
    private final BookOrderRepository orderRepository;
    private final PaymentClient paymentClient;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration retryBackoff;
    private final Duration maximumBackoff;

    public OrderBookSettlementService(
            BookTradeRepository tradeRepository,
            BookOrderRepository orderRepository,
            PaymentClient paymentClient,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${finora.investment.order-book.settlement.retry-backoff:5s}") Duration retryBackoff,
            @Value("${finora.investment.order-book.settlement.maximum-backoff:5m}") Duration maximumBackoff) {
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.paymentClient = paymentClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.retryBackoff = retryBackoff;
        this.maximumBackoff = maximumBackoff;
    }

    /** Thanh toán các lần khớp đã tới hạn. Trả số lần thanh toán thành công. */
    public int settleDue(int batchSize) {
        List<BookTrade> due = tradeRepository.findSettlementDue(clock.instant(), PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            return 0;
        }
        // Đọc lệnh mua của cả lô trong một truy vấn, không mỗi giao dịch một lần.
        Map<Long, BookOrder> bids = orderRepository
                .findAllById(due.stream().map(BookTrade::getBidOrderId).distinct().toList())
                .stream().collect(Collectors.toMap(BookOrder::getId, Function.identity()));

        int settled = 0;
        for (BookTrade trade : due) {
            BookOrder bid = bids.get(trade.getBidOrderId());
            PaymentTransferResult result = callSettle(trade, bid);
            if (result.isSuccess()) {
                settled++;
            }
            record(trade.getId(), result);
        }
        return settled;
    }

    /**
     * Nhả phần tiền giữ còn lại của lệnh mua đã kết thúc. Chỉ chọn lệnh mà mọi lần khớp đã thanh
     * toán xong — Payment nhả toàn bộ phần còn giữ, nhả sớm thì lần thanh toán sau hết tiền.
     */
    public int releaseFinished(int batchSize) {
        int released = 0;
        for (BookOrder order : orderRepository.findReleasePending(PageRequest.of(0, batchSize))) {
            try {
                paymentClient.release(order.getHoldReference(), order.getOrderReference());
            } catch (RuntimeException failure) {
                // Lỗi mạng hoặc Payment từ chối: để nguyên, lần quét sau thử lại. release idempotent.
                log.warn("Chưa nhả được tiền giữ của lệnh mua: orderReference={}, exceptionType={}",
                        order.getOrderReference(), failure.getClass().getName());
                continue;
            }
            transactionTemplate.executeWithoutResult(status -> orderRepository.findById(order.getId())
                    .filter(BookOrder::needsHoldRelease)
                    .ifPresent(fresh -> fresh.markHoldReleased(clock.instant())));
            released++;
            log.info("Nhả phần tiền giữ còn lại: orderReference={}", order.getOrderReference());
        }
        return released;
    }

    private PaymentTransferResult callSettle(BookTrade trade, BookOrder bid) {
        if (bid == null || bid.getHoldReference() == null) {
            // Không thể xảy ra với dữ liệu đúng (CHECK bắt lệnh mua trong sổ phải có tiền giữ).
            return PaymentTransferResult.rejected("BID_HOLD_MISSING", "Lệnh mua không có khoản tiền giữ");
        }
        try {
            return paymentClient.settleFromHold(
                    bid.getHoldReference(),
                    bid.getOrderReference(),
                    trade.getSellerId(),
                    trade.getAmount(),
                    trade.getPlatformFee(),
                    trade.getTradeReference());
        } catch (RuntimeException failure) {
            return PaymentTransferResult.unavailable("Lỗi khi gọi Payment: " + failure.getClass().getSimpleName());
        }
    }

    private void record(Long tradeId, PaymentTransferResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            BookTrade trade = tradeRepository.findById(tradeId).orElseThrow();
            var now = clock.instant();
            if (result.isSuccess()) {
                trade.markSettled(now);
                log.info("Thanh toán lần khớp xong: tradeReference={}", trade.getTradeReference());
            } else if (result.isRetryable()) {
                trade.scheduleRetry(result.getErrorCode(), backoff(trade.getSettlementAttempts()), now);
                log.warn("Thanh toán lần khớp chưa xong, sẽ thử lại: tradeReference={}, errorCode={}, attempts={}",
                        trade.getTradeReference(), result.getErrorCode(), trade.getSettlementAttempts());
            } else {
                // Tiền đã giữ sẵn nên Payment không có lý do nghiệp vụ để từ chối. Nếu vẫn từ chối
                // thì dừng hẳn để đối soát tay — không tự đảo chủ Note hay tự retry vô hạn.
                trade.markFailed(result.getErrorCode(), now);
                log.error("Payment từ chối thanh toán lần khớp, cần đối soát: tradeReference={}, errorCode={}",
                        trade.getTradeReference(), result.getErrorCode());
            }
        });
    }

    /** Backoff lũy thừa có trần: 5s, 10s, 20s ... tối đa 5 phút. */
    Duration backoff(int attempts) {
        long factor = 1L << Math.min(attempts, 16);
        Duration next = retryBackoff.multipliedBy(factor);
        return next.compareTo(maximumBackoff) > 0 ? maximumBackoff : next;
    }
}
