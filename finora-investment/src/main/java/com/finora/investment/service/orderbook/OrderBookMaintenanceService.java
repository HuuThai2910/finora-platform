package com.finora.investment.service.orderbook;

import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentHoldResult;
import com.finora.investment.domain.orderbook.BookOrder;
import com.finora.investment.repository.BookOrderRepository;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Dọn các trạng thái sổ lệnh không tự kết thúc được.
 *
 * <ul>
 *   <li>Lệnh mua kẹt {@code PENDING_FUNDS}: Payment không trả lời lần giữ tiền đầu, người đặt được
 *       báo "gửi lại sau" nhưng app thường sinh khóa mới, nên lệnh cũ không bao giờ được làm tiếp
 *       và khoản giữ (nếu Payment thật ra đã giữ) bị khóa mãi.</li>
 *   <li>Sổ của khoản vay đã tất toán: lệnh còn nằm đó không bao giờ khớp được nữa, lệnh mua vẫn giữ
 *       tiền của người đặt.</li>
 * </ul>
 *
 * <p>Không có transaction ở tầng này: lời gọi Payment nằm ngoài, phần ghi database đi qua
 * {@link OrderBookTransactionService}.</p>
 */
@Slf4j
@Service
public class OrderBookMaintenanceService {

    private final BookOrderRepository orderRepository;
    private final OrderBookTransactionService transactions;
    private final PaymentClient paymentClient;
    private final Clock clock;
    private final Duration pendingFundsTimeout;

    public OrderBookMaintenanceService(
            BookOrderRepository orderRepository,
            OrderBookTransactionService transactions,
            PaymentClient paymentClient,
            Clock clock,
            @Value("${finora.investment.maintenance.pending-funds-timeout:2m}") Duration pendingFundsTimeout) {
        this.orderRepository = orderRepository;
        this.transactions = transactions;
        this.paymentClient = paymentClient;
        this.clock = clock;
        this.pendingFundsTimeout = pendingFundsTimeout;
    }

    /**
     * Kết thúc lệnh mua kẹt chờ giữ tiền quá {@code pending-funds-timeout}.
     *
     * <p>Không biết lần giữ đầu đã thành chưa, nên gọi lại {@code hold} cùng mã lệnh: Payment
     * idempotent theo mã nên trả đúng khoản đã giữ nếu có, hoặc giữ mới nếu chưa. Có
     * {@code holdReference} rồi mới từ chối lệnh để worker thanh toán nhả nó — không bỏ sót khoản
     * giữ nào. Payment từ chối hẳn (ví không đủ) nghĩa là chưa từng giữ: từ chối lệnh, không cần nhả.</p>
     *
     * @return số lệnh đã kết thúc
     */
    public int expireStalePendingBids(int batchSize) {
        int expired = 0;
        var before = clock.instant().minus(pendingFundsTimeout);
        for (BookOrder order : orderRepository.findStalePendingBids(before, PageRequest.of(0, batchSize))) {
            PaymentHoldResult hold = paymentClient.hold(
                    order.getInvestorId(), order.getHoldAmount(), order.getOrderReference());
            if (hold.isSuccess()) {
                if (transactions.expirePendingBid(order.getId(), hold.getHoldReference())) {
                    expired++;
                }
            } else if (!hold.isRetryable()) {
                transactions.rejectBid(order.getId(), hold.getErrorCode(), hold.getErrorMessage());
                expired++;
            } else {
                log.warn("Payment vẫn chưa trả lời khi đối soát lệnh mua: orderReference={}",
                        order.getOrderReference());
            }
        }
        return expired;
    }

    /** Huỷ lệnh còn nằm trong sổ của các khoản vay không còn Note giao dịch được. */
    public int closeFinishedBooks(int batchSize) {
        int cancelled = 0;
        for (Long listingId : orderRepository.findListingsWithRestingOrdersButNoTradableNotes(
                PageRequest.of(0, batchSize))) {
            cancelled += transactions.closeBook(listingId);
        }
        return cancelled;
    }
}
