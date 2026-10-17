package com.finora.investment.service;

import com.finora.investment.domain.order.InvestmentOrder;
import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.OrderResponse;
import com.finora.investment.client.PaymentHoldResult;

/**
 * Các transaction ngắn của luồng đặt lệnh, tách khỏi {@link FundingService}.
 *
 * <p>Tách thành bean riêng là bắt buộc: Spring bọc transaction bằng proxy, nên một method
 * {@code @Transactional} được gọi qua {@code this} từ chính bean đó sẽ <em>không</em> mở
 * transaction nào.</p>
 */
public interface OrderTransactionService {

    /**
     * Bước 1 — ghi lệnh ở {@code PENDING_FUNDS} trước khi chạm vào tiền.
     */
    InvestmentOrder createPendingOrder(
            Long listingId,
            String investorId,
            String idempotencyKey,
            String requestHash,
            PlaceOrderRequest request
    );

    /**
     * Bước 3 — khóa listing, chốt phần vốn và ghi cam kết.
     */
    OrderResponse confirmCommitment(Long orderId, Long listingId, String holdReference);

    /** Ghi nhận kết quả khi Payment không giữ được tiền. */
    OrderResponse rejectForFailedHold(Long orderId, PaymentHoldResult hold);

    /**
     * Hủy lệnh đã cam kết và nhả tiền về ví.
     */
    OrderResponse cancelOrder(String orderReference);

    /** Ghi nhận Payment đã nhả khoản giữ của một lệnh đã kết thúc; gọi lại không đổi gì. */
    void markHoldReleased(Long orderId);

    /** R3: Payment báo khoản giữ của mã lệnh đã được nhả từ trước; từ chối lệnh chờ mà không nhả lại. */
    OrderResponse rejectWithReleasedHold(Long orderId, String holdReference);

    /** R3: khoản giữ của mã lệnh đã vào giải ngân hoặc lệch số tiền; từ chối và dừng ở trạng thái cần vận hành. */
    OrderResponse rejectWithHoldAnomaly(Long orderId, String holdReference, String holdStatus);

    /** R3: lần nhả khoản giữ lỗi; thử lại theo lịch lùi dần, hoặc dừng khi lỗi chắc chắn. */
    void recordReleaseFailure(Long orderId, String errorCode, boolean retryable, java.time.Instant now);

    /** R3: lần làm tiếp lệnh chờ giữ tiền chưa có kết quả; thử lại theo lịch lùi dần. */
    void recordPendingFailure(Long orderId, java.time.Instant now);

    /** R3: admin hẹn worker thử lại ngay lệnh đang dừng hoặc đang lùi; không gọi Payment ở đây. */
    InvestmentOrder retryIncident(String orderReference);
}
