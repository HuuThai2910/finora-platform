package com.finora.investment.dto.response;

import java.util.List;

/**
 * Vị thế của người đang đăng nhập trên một sổ: bao nhiêu Note còn đặt bán được, bao nhiêu đang
 * nằm trong lệnh bán, và các lệnh còn hiệu lực.
 */
public record OrderBookPositionResponse(
        Long listingId,
        long freeNotes,
        long lockedNotes,
        List<BookOrderResponse> activeOrders
) {
}
