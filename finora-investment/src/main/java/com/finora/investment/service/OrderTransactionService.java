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
}
