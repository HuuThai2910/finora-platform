package com.finora.investment.service.impl;

import com.finora.investment.config.InvestorRiskDisclosureProperties;
import com.finora.investment.service.FundingService;
import com.finora.investment.service.InvestorEligibility;
import com.finora.investment.service.OrderTransactionService;
import com.finora.common.enums.investment.OrderStatus;
import com.finora.common.security.SecurityUtils;
import com.finora.investment.domain.order.InvestmentOrder;
import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.OrderResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.client.PaymentCallException;
import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentHoldResult;
import com.finora.investment.client.PaymentHoldView;
import com.finora.investment.mapper.InvestmentMapper;
import com.finora.investment.repository.InvestmentOrderRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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
    private final InvestorEligibility eligibility;
    private final InvestorRiskDisclosureProperties riskDisclosure;

    /**
     * Đặt một lệnh đầu tư vào khoản vay đang gọi vốn.
     *
     * <p>Gửi lại cùng {@code Idempotency-Key} trả về kết quả của lệnh cũ thay vì tạo lệnh mới,
     * để mạng chập chờn hoặc người dùng bấm hai lần không bị trừ tiền hai lần.</p>
     */
    @Override
    public OrderResponse placeOrder(Long listingId, String idempotencyKey, PlaceOrderRequest request) {
        eligibility.requireVerifiedInvestor();
        String investorId = SecurityUtils.getCurrentUserId();
        // Gửi lại cùng khóa là hỏi kết quả của lệnh đã ghi nhận (xác nhận đã lưu lúc tạo lệnh). Chỉ lệnh mới phải mang
        // xác nhận hiện hành; kiểm lại ở lần gửi lại sẽ báo lỗi cho một lệnh đã giữ tiền nếu version vừa đổi.
        if (orderRepository.findByInvestorIdAndIdempotencyKey(investorId, idempotencyKey).isEmpty()) {
            requireRiskAcknowledgement(request);
        }
        return placeOrderFor(investorId, listingId, idempotencyKey, request);
    }

    /**
     * Nhà đầu tư phải xác nhận đúng bản công bố rủi ro hiện hành trước lệnh sơ cấp (D6, LEGAL-DISCLOSURE-02). Version cũ
     * nghĩa là nội dung đã đổi sau khi màn hình tải: từ chối để nhà đầu tư đọc lại, không tự coi là đã đồng ý.
     */
    private void requireRiskAcknowledgement(PlaceOrderRequest request) {
        String version = request.riskAcknowledgementVersion();
        if (version == null || version.isBlank()) {
            throw InvestmentDomainException.invalidInput("RISK_ACKNOWLEDGEMENT_REQUIRED",
                    "Bạn cần xác nhận đã đọc và hiểu công bố rủi ro trước khi góp vốn");
        }
        if (!riskDisclosure.version().equals(version.trim())) {
            throw InvestmentDomainException.conflict("RISK_DISCLOSURE_OUTDATED",
                    "Công bố rủi ro đã được cập nhật, vui lòng đọc lại trước khi góp vốn");
        }
    }

    /**
     * Lõi đặt lệnh, nhận {@code investorId} tường minh thay vì đọc JWT — Auto-Invest chạy trong
     * worker không có người dùng đăng nhập.
     *
     * <p>Gửi lại cùng khóa mà lệnh cũ còn kẹt ở {@code PENDING_FUNDS} (lần trước Payment không
     * phản hồi) thì làm tiếp từ bước giữ tiền: Payment idempotent theo mã lệnh nên không giữ hai
     * lần, và bước chốt cam kết tự kiểm tra lại listing.</p>
     */
    @Override
    public OrderResponse placeOrderFor(
            String investorId, Long listingId, String idempotencyKey, PlaceOrderRequest request) {
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
            if (existing.getStatus() != OrderStatus.PENDING_FUNDS) {
                return mapper.toOrderResponse(existing);
            }
            return holdAndCommit(existing, listingId);
        }

        InvestmentOrder order = transactions.createPendingOrder(
                listingId, investorId, idempotencyKey, requestHash, request);
        return holdAndCommit(order, listingId);
    }

    private OrderResponse holdAndCommit(InvestmentOrder order, Long listingId) {
        // Ngoài transaction: chờ mạng ở đây không khóa nhà đầu tư khác của cùng khoản vay.
        PaymentHoldResult hold = paymentClient.hold(
                order.getInvestorId(), order.getAmount(), order.getOrderReference());

        if (!hold.isSuccess()) {
            if (hold.isRetryable()) {
                // Lỗi tạm thời: không kết luận được tiền đã giữ hay chưa, nên giữ lệnh ở
                // PENDING_FUNDS; FundingMaintenanceService làm tiếp sau pending-funds-timeout.
                log.warn("Giữ tiền chưa có kết quả chắc chắn: orderReference={}",
                        order.getOrderReference());
                throw InvestmentDomainException.conflict(
                        "PAYMENT_UNAVAILABLE",
                        "Dịch vụ ví đang bận, vui lòng kiểm tra lại lệnh sau ít phút"
                );
            }
            if (hold.isAlreadyReleased()) {
                // Payment trả lại khoản giữ cũ của chính mã lệnh nhưng đã nhả (lần chốt trước đã từ chối và nhả rồi mới
                // mất kết quả): không được cam kết trên khoản không còn tiền (R3).
                return transactions.rejectWithReleasedHold(order.getId(), hold.getHoldReference());
            }
            if (hold.isHoldAnomaly()) {
                return transactions.rejectWithHoldAnomaly(order.getId(), hold.getHoldReference(), hold.getHoldStatus());
            }
            return transactions.rejectForFailedHold(order.getId(), hold);
        }

        OrderResponse response = transactions.confirmCommitment(order.getId(), listingId, hold.getHoldReference());
        return releaseIfFinished(order.getOrderReference(), response);
    }

    /**
     * R3: nhả khoản giữ sau khi transaction của lệnh đã commit (bị từ chối sau khi giữ tiền, hoặc người dùng hủy). Nhả trong
     * transaction có thể khiến Payment đã nhả mà Investment rollback. Lỗi ở đây được ghi để worker thử lại theo lịch lùi
     * dần, hoặc dừng cho vận hành khi Payment trả lỗi chắc chắn.
     */
    private OrderResponse releaseIfFinished(String orderReference, OrderResponse response) {
        InvestmentOrder order = orderRepository.findByOrderReference(orderReference).orElse(null);
        if (order == null || order.getPaymentHoldReference() == null || order.getPaymentReleasedAt() != null
                || order.getReleaseBlockedAt() != null
                || (order.getStatus() != OrderStatus.CANCELLED && order.getStatus() != OrderStatus.REJECTED)) {
            return response;
        }
        try {
            paymentClient.release(order.getPaymentHoldReference(), order.getOrderReference());
            transactions.markHoldReleased(order.getId());
        } catch (PaymentCallException failure) {
            transactions.recordReleaseFailure(order.getId(), failure.getCode(), failure.isRetryable(), Instant.now());
            log.warn("Chưa nhả được tiền giữ, worker sẽ xử lý tiếp: orderReference={}, code={}, retryable={}",
                    orderReference, failure.getCode(), failure.isRetryable());
        }
        return mapper.toOrderResponse(orderRepository.findById(order.getId()).orElseThrow());
    }

    @Override
    public OrderResponse resumePendingOrder(Long orderId) {
        InvestmentOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "ORDER_NOT_FOUND", "Không tìm thấy lệnh đầu tư"));
        if (order.getStatus() != OrderStatus.PENDING_FUNDS) {
            return mapper.toOrderResponse(order);
        }
        // R3: hỏi Payment khoản giữ của mã lệnh trước, không đổi gì. Đang giữ đúng số tiền thì chốt luôn; đã nhả hoặc đã
        // vào giải ngân thì không được cam kết; chưa có thì mới gửi lại lời gọi giữ tiền (idempotent theo mã lệnh).
        Optional<PaymentHoldView> existing = paymentClient.findHold(order.getOrderReference());
        if (existing.isPresent()) {
            PaymentHoldView hold = existing.get();
            if (hold.held() && hold.amount() != null && hold.amount().compareTo(order.getAmount()) == 0) {
                OrderResponse response = transactions.confirmCommitment(
                        order.getId(), order.getListingId(), hold.holdReference());
                return releaseIfFinished(order.getOrderReference(), response);
            }
            if (hold.released()) return transactions.rejectWithReleasedHold(order.getId(), hold.holdReference());
            return transactions.rejectWithHoldAnomaly(order.getId(), hold.holdReference(),
                    hold.held() ? "AMOUNT_MISMATCH" : hold.status());
        }
        return holdAndCommit(order, order.getListingId());
    }

    /** Hủy lệnh đã cam kết và nhả tiền về ví. */
    @Override
    public OrderResponse cancelOrder(String orderReference) {
        OrderResponse cancelled = transactions.cancelOrder(orderReference);
        return releaseIfFinished(orderReference, cancelled);
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
