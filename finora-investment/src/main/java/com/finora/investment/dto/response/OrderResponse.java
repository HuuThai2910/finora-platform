package com.finora.investment.dto.response;

import java.time.Instant;

/**
 * Kết quả một lệnh đặt vốn.
 *
 * <p>{@code rejectedReasonCode} cho client biết vì sao lệnh trượt để hiển thị đúng hướng
 * dẫn — hết chỗ thì mời chọn khoản khác, thiếu số dư thì mời nạp tiền.</p>
 */
public record OrderResponse(
        String orderReference,
        Long listingId,
        String amount,
        String status,
        String paymentHoldReference,
        String rejectedReasonCode,
        String rejectedReasonDetail,
        Instant createdAt
) {
}
