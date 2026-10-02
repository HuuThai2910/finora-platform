package com.finora.investment.dto.response;

import java.time.Instant;

/**
 * Một lệnh trên sổ, trả cho chính người đặt. Tiền là chuỗi decimal, giá là chuỗi % một chữ số
 * thập phân (ví dụ {@code "97.5"}).
 *
 * @param loanId         khoản vay gốc của sổ, để danh sách lệnh trên nhiều sổ gọi đúng tên khoản vay
 * @param holdAmount     số tiền đã giữ khi đặt lệnh mua; null với lệnh bán
 * @param holdConsumed   phần tiền giữ đã dùng cho các lần khớp
 * @param holdReleased   phần tiền giữ còn lại đã được nhả về ví hay chưa
 */
public record BookOrderResponse(
        String orderReference,
        Long listingId,
        Long loanId,
        String side,
        String pricePercent,
        int quantity,
        int filledQuantity,
        int remainingQuantity,
        String status,
        String holdAmount,
        String holdConsumed,
        boolean holdReleased,
        String cancelReason,
        String rejectReasonCode,
        String rejectReasonDetail,
        Instant createdAt,
        Instant updatedAt
) {
}
