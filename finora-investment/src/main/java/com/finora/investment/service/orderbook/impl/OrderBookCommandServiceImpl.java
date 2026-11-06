package com.finora.investment.service.orderbook.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.orderbook.entity.BookOrder;
import com.finora.investment.domain.orderbook.enums.BookOrderStatus;
import com.finora.investment.domain.orderbook.enums.OrderSide;
import com.finora.investment.dto.request.PlaceBookOrderRequest;
import com.finora.investment.dto.response.BookOrderResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.integration.payment.client.PaymentClient;
import com.finora.investment.integration.payment.contract.PaymentHoldResult;
import com.finora.investment.repository.BookOrderRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.service.InvestorEligibility;
import com.finora.investment.service.orderbook.OrderBookCommandService;
import com.finora.investment.service.orderbook.OrderBookTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Điều phối đặt và huỷ lệnh trên sổ lệnh Notes. Không có transaction ở tầng này: transaction thật
 * nằm ở {@link OrderBookTransactionService}, tách nhau bởi lời gọi Payment.
 *
 * <p>Lệnh mua đi ba bước, cùng mẫu với đặt vốn sơ cấp (F04) — <strong>không giữ database
 * transaction trong lúc chờ mạng</strong>:</p>
 * <ol>
 *   <li>Ghi lệnh {@code PENDING_FUNDS} kèm số tiền cần giữ.</li>
 *   <li>Gọi Payment giữ tiền, ngoài transaction, dùng mã lệnh làm khóa idempotency.</li>
 *   <li>Khóa sổ, cho lệnh vào sổ và khớp.</li>
 * </ol>
 *
 * <p>Lệnh bán không cần giữ tiền (Note của người bán bị khóa thay cho tiền), nên chỉ một
 * transaction.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBookCommandServiceImpl implements OrderBookCommandService {

    private final OrderBookTransactionService orderBookTransactionService;
    private final BookOrderRepository bookOrderRepository;
    private final PaymentClient paymentClient;
    private final MarketListingRepository marketListingRepository;
    private final InvestorEligibility investorEligibility;

    @Override
    public BookOrderResponse placeOrder(Long listingId, String idempotencyKey, PlaceBookOrderRequest request) {
        investorEligibility.requireVerifiedInvestor();
        String investorId = SecurityUtils.getCurrentUserId();
        int pricePermille = OrderBookPricing.toPermille(request.pricePercent());
        String requestHash = hashRequest(listingId, request.side(), pricePermille, request.quantity());

        Optional<BookOrder> replayed = bookOrderRepository.findByInvestorIdAndIdempotencyKey(investorId, idempotencyKey);
        if (replayed.isPresent()) {
            return resume(replayed.get(), requestHash);
        }

        var command = new OrderBookTransactionService.OrderCommand(
                listingId, investorId, request.side(), pricePermille, request.quantity(),
                Boolean.TRUE.equals(request.acknowledgeDefault()), idempotencyKey, requestHash);

        try {
            if (request.side() == OrderSide.ASK) {
                return respond(orderBookTransactionService.placeAsk(command));
            }
            return holdAndActivate(orderBookTransactionService.createPendingBid(command));
        } catch (DataIntegrityViolationException duplicate) {
            // Hai request cùng khóa đến cùng lúc: request đến sau vấp unique (investor, key) và
            // nhận về lệnh của request đến trước, thay vì báo lỗi cho một thao tác thật ra đã thành.
            return bookOrderRepository.findByInvestorIdAndIdempotencyKey(investorId, idempotencyKey)
                    .map(existing -> resume(existing, requestHash))
                    .orElseThrow(() -> duplicate);
        }
    }

    @Override
    public BookOrderResponse cancelOrder(String orderReference) {
        return respond(orderBookTransactionService.cancel(orderReference, SecurityUtils.getCurrentUserId()));
    }

    private BookOrderResponse resume(BookOrder existing, String requestHash) {
        // Cùng khóa nhưng khác nội dung là lỗi phía client: trả kết quả cũ sẽ khiến người dùng
        // tưởng lệnh mới đã đặt thành công.
        if (!requestHash.equals(existing.getRequestHash())) {
            throw InvestmentDomainException.conflict(
                    "IDEMPOTENCY_KEY_REUSED", "Khóa idempotency đã được dùng cho một lệnh khác");
        }
        if (existing.getStatus() == BookOrderStatus.PENDING_FUNDS) {
            return holdAndActivate(existing);
        }
        return respond(existing);
    }

    private BookOrderResponse holdAndActivate(BookOrder order) {
        // Ngoài transaction: chờ Payment ở đây không khóa sổ của ai.
        PaymentHoldResult hold = paymentClient.hold(
                order.getInvestorId(), order.getHoldAmount(), order.getOrderReference());

        if (!hold.isSuccess()) {
            if (hold.isRetryable()) {
                // Không biết tiền đã giữ hay chưa: để lệnh ở PENDING_FUNDS, gửi lại cùng khóa sẽ
                // làm tiếp. Payment idempotent theo mã lệnh nên không giữ hai lần.
                log.warn("Giữ tiền cho lệnh mua chưa có kết quả chắc chắn: orderReference={}",
                        order.getOrderReference());
                throw InvestmentDomainException.conflict(
                        "PAYMENT_UNAVAILABLE", "Dịch vụ ví đang bận, vui lòng gửi lại lệnh sau ít phút");
            }
            return respond(
                    orderBookTransactionService.rejectBid(order.getId(), hold.getErrorCode(), hold.getErrorMessage()));
        }

        return respond(
                orderBookTransactionService.activateBid(order.getId(), order.getListingId(), hold.getHoldReference()));
    }

    private BookOrderResponse respond(BookOrder order) {
        Long loanId = marketListingRepository.findById(order.getListingId()).map(MarketListing::getLoanId).orElse(null);
        return OrderBookMapper.toResponse(order, loanId);
    }

    /** Vân tay nội dung lệnh, gắn với khóa idempotency để phát hiện dùng lại khóa cho lệnh khác. */
    private static String hashRequest(Long listingId, OrderSide side, int pricePermille, int quantity) {
        String canonical = listingId + "|" + side + "|" + pricePermille + "|" + quantity;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Thiếu thuật toán SHA-256", e);
        }
    }
}
