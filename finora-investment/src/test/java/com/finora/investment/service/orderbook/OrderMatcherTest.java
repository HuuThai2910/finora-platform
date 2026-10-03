package com.finora.investment.service.orderbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.investment.domain.orderbook.BookOrder;
import com.finora.investment.domain.orderbook.BookOrderStatus;
import com.finora.investment.domain.orderbook.OrderSide;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Thứ tự ưu tiên giá–thời gian, khớp từng phần và chặn tự khớp — kiểm trên thuật toán thuần, không
 * cần database.
 */
class OrderMatcherTest {

    private final AtomicLong sequence = new AtomicLong();

    private BookOrder order(String investor, OrderSide side, int permille, int quantity) {
        return BookOrder.builder()
                .orderReference("OB-" + sequence.incrementAndGet())
                .investorId(investor)
                .side(side)
                .pricePermille(permille)
                .quantity(quantity)
                .filledQuantity(0)
                .status(BookOrderStatus.OPEN)
                .sequence(sequence.get())
                .holdAmount(side == OrderSide.BID ? new BigDecimal("1000000.00") : null)
                .holdConsumed(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("Lệnh mua lớn quét lần lượt các lệnh bán theo thứ tự truyền vào, khớp từng phần lệnh cuối")
    void sweepsRestingInPriorityOrder() {
        BookOrder cheapest = order("S1", OrderSide.ASK, 950, 2);
        BookOrder next = order("S2", OrderSide.ASK, 960, 3);
        BookOrder bid = order("B1", OrderSide.BID, 970, 4);

        OrderMatcher.MatchPlan plan = OrderMatcher.plan(bid, List.of(cheapest, next));

        assertThat(plan.selfTradeStopped()).isFalse();
        assertThat(plan.fills()).extracting(OrderMatcher.Fill::resting).containsExactly(cheapest, next);
        assertThat(plan.fills()).extracting(OrderMatcher.Fill::quantity).containsExactly(2, 2);
    }

    @Test
    @DisplayName("Lệnh mới nhỏ hơn lệnh nằm chờ: khớp hết lệnh mới, không chạm lệnh sau")
    void stopsWhenIncomingFilled() {
        BookOrder first = order("B1", OrderSide.BID, 980, 10);
        BookOrder second = order("B2", OrderSide.BID, 980, 10);
        BookOrder ask = order("S1", OrderSide.ASK, 970, 3);

        OrderMatcher.MatchPlan plan = OrderMatcher.plan(ask, List.of(first, second));

        assertThat(plan.fills()).hasSize(1);
        assertThat(plan.fills().get(0).resting()).isSameAs(first);
        assertThat(plan.fills().get(0).quantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("Chạm lệnh của chính mình thì dừng, giữ các lần khớp trước đó")
    void stopsAtOwnOrder() {
        BookOrder other = order("S1", OrderSide.ASK, 950, 1);
        BookOrder mine = order("B1", OrderSide.ASK, 955, 5);
        BookOrder behind = order("S2", OrderSide.ASK, 960, 5);
        BookOrder bid = order("B1", OrderSide.BID, 990, 5);

        OrderMatcher.MatchPlan plan = OrderMatcher.plan(bid, List.of(other, mine, behind));

        assertThat(plan.selfTradeStopped()).isTrue();
        assertThat(plan.fills()).extracting(OrderMatcher.Fill::resting).containsExactly(other);
    }

    @Test
    @DisplayName("Đã khớp một phần thì chỉ tính phần còn lại của lệnh mới")
    void usesRemainingQuantity() {
        BookOrder bid = order("B1", OrderSide.BID, 990, 5);
        bid.fill(3, BigDecimal.ZERO, java.time.Instant.EPOCH);
        BookOrder ask = order("S1", OrderSide.ASK, 950, 10);

        OrderMatcher.MatchPlan plan = OrderMatcher.plan(bid, List.of(ask));

        assertThat(plan.fills().get(0).quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("Lệnh không khớp được giá lọt vào danh sách là lỗi lập trình, không âm thầm khớp")
    void rejectsNonCrossingResting() {
        BookOrder ask = order("S1", OrderSide.ASK, 990, 1);
        BookOrder bid = order("B1", OrderSide.BID, 950, 1);

        assertThatThrownBy(() -> OrderMatcher.plan(bid, List.of(ask)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Giá bằng nhau là khớp được ở cả hai chiều")
    void equalPricesCross() {
        assertThat(OrderMatcher.crosses(order("B", OrderSide.BID, 950, 1), order("S", OrderSide.ASK, 950, 1))).isTrue();
        assertThat(OrderMatcher.crosses(order("S", OrderSide.ASK, 950, 1), order("B", OrderSide.BID, 950, 1))).isTrue();
        assertThat(OrderMatcher.crosses(order("S", OrderSide.ASK, 951, 1), order("B", OrderSide.BID, 950, 1))).isFalse();
    }
}
