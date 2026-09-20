package com.finora.investment.service.impl;

import com.finora.investment.service.FundingService;
import com.finora.investment.service.OrderTransactionService;
import com.finora.common.security.SecurityUtils;
import com.finora.investment.domain.order.InvestmentOrder;
import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.OrderResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentHoldResult;
import com.finora.investment.mapper.InvestmentMapper;
import com.finora.investment.repository.InvestmentOrderRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Luồng gọi vốn F04: đặt lệnh, giữ tiền, tạo cam kết.
 *
 * <p>Điểm khó nhất của use case này là <strong>không được giữ database transaction trong lúc
 * chờ mạng</strong> (08-cross-service-flows.md). Gọi Payment mất hàng trăm mili giây; nếu làm
 * việc đó khi đang khóa listing thì mọi nhà đầu tư khác của cùng khoản vay bị chặn theo.</p>
 *
 * <p>Vì vậy một lệnh đặt vốn được chia thành ba transaction ngắn, tách nhau bởi lời gọi
 * Payment. Class này giữ phần điều phối không có transaction; ba transaction thật nằm ở
 * {@link OrderTransactionService}:</p>
 * <ol>
 *   <li><b>Ghi lệnh</b> — tạo lệnh ở {@code PENDING_FUNDS}, chưa đụng tiền, chưa cộng tiến độ.</li>
 *   <li><b>Giữ tiền</b> — gọi Payment ngoài transaction, dùng mã lệnh làm khóa idempotency.</li>
 *   <li><b>Chốt cam kết</b> — khóa listing, kiểm tra lại phần vốn còn lại rồi ghi commitment.
 *       Thất bại ở bước này thì tiền vừa giữ được nhả ra ngay.</li>
 * </ol>
 *
 * <p>Kiểm tra phần vốn còn lại nằm ở bước 3 chứ không phải bước 1, vì giữa hai bước có thể
 * có người khác mua mất. Bước 1 chỉ kiểm tra sơ bộ để không gọi Payment một cách vô ích.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundingServiceImpl implements FundingService {

    private final InvestmentOrderRepository orderRepository;
    private final OrderTransactionService transactions;
    private final PaymentClient paymentClient;
    private final InvestmentMapper mapper;

    /**
     * Đặt một lệnh đầu tư vào khoản vay đang gọi vốn.
     *
     * <p>Gửi lại cùng {@code Idempotency-Key} trả về kết quả của lệnh cũ thay vì tạo lệnh mới,
     * để mạng chập chờn hoặc người dùng bấm hai lần không bị trừ tiền hai lần.</p>
     */
    @Override
    public OrderResponse placeOrder(Long listingId, String idempotencyKey, PlaceOrderRequest request) {
        String investorId = SecurityUtils.getCurrentUserId();
        String requestHash = hashRequest(listingId, request.amount());

        Optional<InvestmentOrder> replayed = orderRepository
                .findByInvestorIdAndIdempotencyKey(investorId, idempotencyKey);
        if (replayed.isPresent()) {
            InvestmentOrder existing = replayed.get();
            // Cùng khóa nhưng khác nội dung là lỗi phía client: trả kết quả cũ sẽ khiến
            // người dùng tưởng lệnh mới đã đặt thành công.
            if (!requestHash.equals(existing.getRequestHash())) {
                throw InvestmentDomainException.conflict(
                        "IDEMPOTENCY_KEY_REUSED",
                        "Khóa idempotency đã được dùng cho một lệnh khác"
                );
            }
            return mapper.toOrderResponse(existing);
        }

        InvestmentOrder order = transactions.createPendingOrder(
                listingId, investorId, idempotencyKey, requestHash, request);

        // Ngoài transaction: chờ mạng ở đây không khóa nhà đầu tư khác của cùng khoản vay.
        PaymentHoldResult hold = paymentClient.hold(
                investorId, order.getAmount(), order.getOrderReference());

        if (!hold.isSuccess()) {
            if (hold.isRetryable()) {
                // Lỗi tạm thời: không kết luận được tiền đã giữ hay chưa, nên giữ lệnh ở
                // PENDING_FUNDS cho luồng đối soát xử lý thay vì hủy oan của nhà đầu tư.
                log.warn("Giữ tiền chưa có kết quả chắc chắn: orderReference={}",
                        order.getOrderReference());
                throw InvestmentDomainException.conflict(
                        "PAYMENT_UNAVAILABLE",
                        "Dịch vụ ví đang bận, vui lòng kiểm tra lại lệnh sau ít phút"
                );
            }
            return transactions.rejectForFailedHold(order.getId(), hold);
        }

        return transactions.confirmCommitment(order.getId(), listingId, hold.getHoldReference());
    }

    /** Hủy lệnh đã cam kết và nhả tiền về ví. */
    @Override
    public OrderResponse cancelOrder(String orderReference) {
        return transactions.cancelOrder(orderReference);
    }

    /**
     * Vân tay nội dung request, gắn với khóa idempotency.
     *
     * <p>Dùng để phát hiện client vô tình dùng lại một khóa cũ cho số tiền khác.</p>
     */
    private String hashRequest(Long listingId, BigDecimal amount) {
        String canonical = listingId + "|" + amount.stripTrailingZeros().toPlainString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Thiếu thuật toán SHA-256", e);
        }
    }
}
