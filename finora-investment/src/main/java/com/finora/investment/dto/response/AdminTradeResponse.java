package com.finora.investment.dto.response;

import java.time.Instant;

/**
 * Một lần khớp trên sổ lệnh, nhìn từ màn quản trị: ai mua của ai, bao nhiêu, và đã thanh toán chưa.
 *
 * <p>Chỉ trả qua {@code /investments/admin/**}: khác ảnh chụp công khai của sổ, ở đây có mã nhà đầu
 * tư hai bên để quản trị đối soát khi thanh toán lỗi.</p>
 *
 * @param aggressorSide        bên chủ động khớp ({@code BID} người mua vừa đặt, {@code ASK} người bán vừa đặt)
 * @param settlementStatus     {@code PENDING} chờ Payment, {@code SETTLED} đã chuyển tiền, {@code FAILED} cần đối soát tay
 * @param lastSettlementError  mã lỗi Payment trả ở lần thử gần nhất, nếu có
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
        Instant settledAt
) {
}
