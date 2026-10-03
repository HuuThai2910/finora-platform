package com.finora.investment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.investment.domain.orderbook.BookOrder;
import com.finora.investment.domain.orderbook.BookOrderStatus;
import com.finora.investment.domain.orderbook.CancelReason;
import com.finora.investment.domain.orderbook.OrderSide;
import com.finora.investment.exception.InvestmentDomainException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Chuyển trạng thái của lệnh trên sổ chỉ đi theo đường đã whitelist. */
class BookOrderTest {

    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z");

    private BookOrder pendingBid(String hold) {
        return BookOrder.builder()
                .orderReference("OB-1")
                .investorId("B1")
                .side(OrderSide.BID)
                .pricePermille(970)
                .quantity(4)
                .filledQuantity(0)
                .status(BookOrderStatus.PENDING_FUNDS)
                .holdAmount(new BigDecimal(hold))
                .holdConsumed(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("Lệnh mua: giữ tiền → vào sổ → khớp một phần → khớp hết")
    void bidLifecycle() {
        BookOrder bid = pendingBid("4000.00");
        bid.activate("HOLD-1", 7, NOW);
        assertThat(bid.getStatus()).isEqualTo(BookOrderStatus.OPEN);
        assertThat(bid.getSequence()).isEqualTo(7);

        bid.fill(1, new BigDecimal("950.00"), NOW);
        assertThat(bid.getStatus()).isEqualTo(BookOrderStatus.PARTIALLY_FILLED);
        assertThat(bid.holdRemaining()).isEqualByComparingTo("3050.00");

        bid.fill(3, new BigDecimal("2900.00"), NOW);
        assertThat(bid.getStatus()).isEqualTo(BookOrderStatus.FILLED);
        assertThat(bid.needsHoldRelease()).isTrue();
    }

    @Test
    @DisplayName("Khớp vượt số tiền đã giữ là vi phạm bất biến tiền, dừng hẳn")
    void fillCannotExceedHold() {
        BookOrder bid = pendingBid("1000.00");
        bid.activate("HOLD-1", 1, NOW);

        assertThatThrownBy(() -> bid.fill(1, new BigDecimal("1000.01"), NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bid.getFilledQuantity()).isZero();
    }

    @Test
    @DisplayName("Không huỷ được lệnh đang chờ giữ tiền hoặc đã khớp hết")
    void cancelOnlyRestingOrders() {
        BookOrder pending = pendingBid("1000.00");
        assertThatThrownBy(() -> pending.cancel(CancelReason.USER, "B1", NOW))
                .isInstanceOf(InvestmentDomainException.class);

        BookOrder filled = pendingBid("4000.00");
        filled.activate("HOLD-1", 1, NOW);
        filled.fill(4, new BigDecimal("3800.00"), NOW);
        assertThatThrownBy(() -> filled.cancel(CancelReason.USER, "B1", NOW))
                .isInstanceOf(InvestmentDomainException.class);
    }

    @Test
    @DisplayName("Huỷ lệnh khớp một phần giữ phần đã khớp; lệnh mua cần nhả phần tiền còn lại")
    void cancelKeepsFilledPart() {
        BookOrder bid = pendingBid("4000.00");
        bid.activate("HOLD-1", 1, NOW);
        bid.fill(1, new BigDecimal("950.00"), NOW);

        bid.cancel(CancelReason.USER, "B1", NOW);

        assertThat(bid.getStatus()).isEqualTo(BookOrderStatus.CANCELLED);
        assertThat(bid.getFilledQuantity()).isEqualTo(1);
        assertThat(bid.needsHoldRelease()).isTrue();
        bid.markHoldReleased(NOW);
        assertThat(bid.needsHoldRelease()).isFalse();
    }

    @Test
    @DisplayName("Chỉ từ chối được lệnh còn chờ giữ tiền")
    void rejectOnlyPending() {
        BookOrder bid = pendingBid("1000.00");
        bid.reject("INSUFFICIENT_BALANCE", "Số dư không đủ", NOW);
        assertThat(bid.getStatus()).isEqualTo(BookOrderStatus.REJECTED);
        assertThatThrownBy(() -> bid.activate("HOLD-1", 1, NOW))
                .isInstanceOf(InvestmentDomainException.class);
    }
}
