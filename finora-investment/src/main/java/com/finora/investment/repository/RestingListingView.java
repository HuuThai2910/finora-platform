package com.finora.investment.repository;

import com.finora.investment.domain.orderbook.enums.OrderSide;

/**
 * Lệnh còn chờ khớp của một phía trong một sổ: số lệnh, số phần vốn chờ, giá cao nhất và thấp nhất (giá mua tốt nhất là
 * {@code maxPermille} của phía mua, giá bán tốt nhất là {@code minPermille} của phía bán).
 */
public record RestingListingView(Long listingId, OrderSide side, Long orders, Long notes, Integer maxPermille,
                                 Integer minPermille) {
}
