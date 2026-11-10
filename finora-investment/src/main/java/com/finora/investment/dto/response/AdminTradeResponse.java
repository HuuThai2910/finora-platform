package com.finora.investment.dto.response;

import java.time.Instant;

/**
 * Một lần khớp trên sổ lệnh, nhìn từ màn quản trị: ai mua của ai, bao nhiêu, đã thanh toán và lập giấy xác nhận chưa.
 *
 * <p>Chỉ trả qua {@code /investments/admin/**}: khác ảnh chụp công khai của sổ, ở đây có mã nhà đầu
 * tư hai bên (để mở hồ sơ khách hàng) và họ tên của họ.</p>
 *
 * @param aggressorSide        bên chủ động khớp ({@code BID} người mua vừa đặt, {@code ASK} người bán vừa đặt)
 * @param settlementStatus     {@code PENDING} chờ Payment, {@code SETTLED} đã chuyển tiền, {@code FAILED} cần đối soát tay
 * @param lastSettlementError  mã lỗi Payment trả ở lần thử gần nhất, nếu có
 * @param outstandingPerNote   dư nợ gốc mỗi phần vốn lúc khớp (giá khớp tính theo con số này)
 * @param documentStatus       trạng thái giấy xác nhận; null khi chưa có dòng văn bản (chưa thanh toán xong)
 * @param progress             chặng gộp {@code SETTLING}, {@code DOC_PENDING}, {@code DONE} (xem {@code TradeProgress})
 */
public record AdminTradeResponse(
        String tradeReference,
        Long listingId,
        Long loanId,
        String buyerId,
        String sellerId,
        String aggressorSide,
        String pricePercent,
        int quantity,
        String amount,
        String platformFee,
        String sellerProceeds,
        boolean defaulted,
        String settlementStatus,
        int settlementAttempts,
        String lastSettlementError,
        Instant executedAt,
        Instant settledAt,
        /** Số lần nhân viên đã gửi thanh toán lại và thời điểm gần nhất. */
        int settlementRequeueCount,
        Instant settlementRequeuedAt,
        /** Khoản vay của sổ: mã hiển thị và mục đích, để bảng và trang chi tiết không gọi thêm API. */
        String applicationCode,
        String purpose,
        String purposeCode,
        /** Họ tên hai bên; null khi User chưa đọc được. */
        String buyerName,
        String sellerName,
        String outstandingPerNote,
        String documentStatus,
        String documentNumber,
        Instant documentIssuedAt,
        String progress
) {
}
