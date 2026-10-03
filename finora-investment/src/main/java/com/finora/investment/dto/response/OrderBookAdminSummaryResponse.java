package com.finora.investment.dto.response;

/**
 * Dải số liệu đầu trang quản trị chợ Notes, tính trên toàn bộ dữ liệu (không phải trang đang xem).
 *
 * @param settledAmount  tổng tiền các lần khớp đã thanh toán xong
 * @param feeCollected   tổng phí nền tảng của các lần khớp đã thanh toán xong
 * @param pendingCount   số lần khớp đang chờ Payment chuyển tiền
 * @param failedCount    số lần khớp Payment từ chối, cần đối soát tay
 * @param openBidNotes   tổng số Note các lệnh mua đang chờ khớp
 * @param openAskNotes   tổng số Note các lệnh bán đang chờ khớp
 */
public record OrderBookAdminSummaryResponse(
        long settledCount,
        String settledAmount,
        String feeCollected,
        long pendingCount,
        String pendingAmount,
        long failedCount,
        long openBidOrders,
        long openBidNotes,
        long openAskOrders,
        long openAskNotes
) {
}
