package com.finora.investment.repository;

import com.finora.investment.domain.orderbook.SettlementStatus;
import java.math.BigDecimal;

/** Tổng các lần khớp của một trạng thái thanh toán. */
public record SettlementTotalsView(SettlementStatus status, Long count, BigDecimal amount, BigDecimal platformFee) {
}
