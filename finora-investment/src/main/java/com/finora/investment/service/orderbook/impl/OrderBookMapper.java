package com.finora.investment.service.orderbook.impl;

import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.orderbook.entity.BookOrder;
import com.finora.investment.dto.response.BookOrderResponse;

/** Chuyển lệnh trên sổ sang DTO trả cho người đặt. */
final class OrderBookMapper {

    private OrderBookMapper() {
    }

    /** @param listing khoản vay gốc của sổ; null khi không còn tìm thấy (khi đó không có mã và mục đích) */
    static BookOrderResponse toResponse(BookOrder order, MarketListing listing) {
        return new BookOrderResponse(
                order.getOrderReference(),
                order.getListingId(),
                listing == null ? null : listing.getLoanId(),
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
                order.getUpdatedAt(),
                listing == null ? null : listing.getPurpose());
    }
}
