package com.finora.investment.domain.orderbook.entity;

import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gửi thanh toán lại lần khớp "Cần đối soát" (đợt 2 web quản trị). */
class BookTradeRequeueTest {

    private static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");

    @Test
    void failedTradeGoesBackToPendingDueNow() {
        BookTrade trade = BookTrade.builder().tradeReference("TRD-1").settlementStatus(SettlementStatus.PENDING)
                .settlementAttempts(0).build();
        trade.markFailed("PAYMENT_REJECTED", NOW.minusSeconds(60));

        trade.requeueSettlement("staff-1", NOW);

        assertThat(trade.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(trade.getNextSettlementAt()).isEqualTo(NOW);
        assertThat(trade.getSettlementRequeueCount()).isEqualTo(1);
        assertThat(trade.getSettlementRequeuedBy()).isEqualTo("staff-1");
        // Giữ mã lỗi gần nhất để người xem biết vì sao từng phải gửi lại.
        assertThat(trade.getLastSettlementError()).isEqualTo("PAYMENT_REJECTED");
    }

    @Test
    void onlyFailedTradeCanBeRequeued() {
        BookTrade trade = BookTrade.builder().tradeReference("TRD-2").settlementStatus(SettlementStatus.PENDING)
                .settlementAttempts(0).build();

        assertThatThrownBy(() -> trade.requeueSettlement("staff-1", NOW)).isInstanceOf(IllegalStateException.class);
    }
}
