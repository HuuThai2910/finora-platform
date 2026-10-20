package com.finora.investment.dto.response;

import com.finora.investment.domain.order.InvestmentOrder;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lệnh góp vốn chưa khớp với ví ở Payment, cho màn sự cố của admin (R3).
 *
 * @param issue {@code HOLD_PENDING}: chưa biết tiền đã giữ hay chưa, worker đang hỏi lại Payment;
 *              {@code RELEASE_RETRYING}: lệnh đã kết thúc, nhả tiền giữ lỗi và đang thử lại;
 *              {@code RELEASE_BLOCKED}: Payment trả lỗi chắc chắn, worker dừng, cần vận hành kiểm tra
 */
public record OrderIncidentResponse(
        String orderReference,
        Long listingId,
        String investorId,
        BigDecimal amount,
        String status,
        String issue,
        String paymentHoldReference,
        String rejectedReasonCode,
        String releaseErrorCode,
        int releaseAttemptCount,
        Instant releaseNextAttemptAt,
        Instant releaseBlockedAt,
        int pendingAttemptCount,
        Instant pendingNextAttemptAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static OrderIncidentResponse from(InvestmentOrder order) {
        String issue = order.getReleaseBlockedAt() != null ? "RELEASE_BLOCKED"
                : "PENDING_FUNDS".equals(order.getStatus().name()) ? "HOLD_PENDING" : "RELEASE_RETRYING";
        return new OrderIncidentResponse(order.getOrderReference(), order.getListingId(), order.getInvestorId(),
                order.getAmount(), order.getStatus().name(), issue, order.getPaymentHoldReference(),
                order.getRejectedReasonCode(), order.getReleaseErrorCode(), order.getReleaseAttemptCount(),
                order.getReleaseNextAttemptAt(), order.getReleaseBlockedAt(), order.getPendingAttemptCount(),
                order.getPendingNextAttemptAt(), order.getCreatedAt(), order.getUpdatedAt());
    }
}
