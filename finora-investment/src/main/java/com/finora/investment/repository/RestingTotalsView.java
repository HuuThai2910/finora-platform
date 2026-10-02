package com.finora.investment.repository;

import com.finora.investment.domain.orderbook.OrderSide;

/** Lệnh còn chờ khớp của một chiều trên toàn chợ: số lệnh và số Note. */
public record RestingTotalsView(OrderSide side, Long orders, Long notes) {
}
