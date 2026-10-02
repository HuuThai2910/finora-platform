package com.finora.investment.repository;

import com.finora.investment.domain.orderbook.OrderSide;

/**
 * Giá cao nhất và thấp nhất của một phía trong một sổ. Phía mua dùng {@code maxPermille} làm giá
 * mua tốt nhất, phía bán dùng {@code minPermille} làm giá bán tốt nhất.
 */
public record BestPriceView(Long orderBookId, OrderSide side, Integer maxPermille, Integer minPermille) {
}
