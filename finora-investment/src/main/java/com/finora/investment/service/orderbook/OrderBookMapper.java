package com.finora.investment.service.orderbook;

import com.finora.investment.domain.orderbook.BookOrder;
import com.finora.investment.dto.response.BookOrderResponse;

/** Chuyển lệnh trên sổ sang DTO trả cho người đặt. */
final class OrderBookMapper {

    private OrderBookMapper() {
    }

    static BookOrderResponse toResponse(BookOrder order, Long loanId) {
        return new BookOrderResponse(
                order.getOrderReference(),
                order.getListingId(),
                loanId,
                order.getSide().name(),
                OrderBookPricing.toPercent(order.getPricePermille()),
                order.getQuantity(),
                order.getFilledQuantity(),
                order.remainingQuantity(),
                order.getStatus().name(),
                order.getHoldAmount() == null ? null : order.getHoldAmount().toPlainString(),
                order.getHoldAmount() == null ? null : order.getHoldConsumed().toPlainString(),
                order.getHoldReleasedAt() != null,
                order.getCancelReason() == null ? null : order.getCancelReason().name(),
                order.getRejectReasonCode(),
                order.getRejectReasonDetail(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
