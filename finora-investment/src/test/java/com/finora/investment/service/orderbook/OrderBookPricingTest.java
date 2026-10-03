package com.finora.investment.service.orderbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.investment.exception.InvestmentDomainException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderBookPricingTest {

    @Test
    @DisplayName("Giá % đổi sang phần nghìn và ngược lại")
    void convertsPercent() {
        assertThat(OrderBookPricing.toPermille(new BigDecimal("97.5"))).isEqualTo(975);
        assertThat(OrderBookPricing.toPermille(new BigDecimal("100"))).isEqualTo(1000);
        assertThat(OrderBookPricing.toPermille(new BigDecimal("0.1"))).isEqualTo(1);
        assertThat(OrderBookPricing.toPercent(975)).isEqualTo("97.5");
        assertThat(OrderBookPricing.toPercent(1000)).isEqualTo("100.0");
    }

    @Test
    @DisplayName("Giá lệch bước 0,1% hoặc ngoài khoảng 0,1%–100% bị từ chối")
    void rejectsInvalidPrice() {
        assertThatThrownBy(() -> OrderBookPricing.toPermille(new BigDecimal("97.55")))
                .isInstanceOf(InvestmentDomainException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_PRICE_TICK");
        assertThatThrownBy(() -> OrderBookPricing.toPermille(new BigDecimal("100.1")))
                .isInstanceOf(InvestmentDomainException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_PRICE_OUT_OF_RANGE");
        assertThatThrownBy(() -> OrderBookPricing.toPermille(BigDecimal.ZERO))
                .isInstanceOf(InvestmentDomainException.class);
    }

    @Test
    @DisplayName("Ví dụ INV-E1: Note dư nợ 1.000.000đ bán 95% → 950.000đ, phí 47.500đ")
    void amountAndFee() {
        BigDecimal amount = OrderBookPricing.amountForNote(new BigDecimal("1000000.00"), 950);
        assertThat(amount).isEqualByComparingTo("950000.00");
        assertThat(OrderBookPricing.platformFee(amount)).isEqualByComparingTo("47500.00");
    }

    @Test
    @DisplayName("Làm tròn: tiền HALF_UP tới đồng lẻ, phí làm tròn xuống")
    void rounding() {
        // 333.333,33 × 97,5% = 324.999,9967… → 325.000,00
        BigDecimal amount = OrderBookPricing.amountForNote(new BigDecimal("333333.33"), 975);
        assertThat(amount).isEqualByComparingTo("325000.00");
        // 5% của 10,01 = 0,5005 → 0,50
        assertThat(OrderBookPricing.platformFee(new BigDecimal("10.01"))).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("Tiền giữ của lệnh mua là trần cho mọi lần khớp có giá và dư nợ không cao hơn")
    void holdCoversEveryFill() {
        BigDecimal maxOutstanding = new BigDecimal("1234567.89");
        BigDecimal hold = OrderBookPricing.holdAmount(maxOutstanding, 987, 3);
        for (int price = 1; price <= 987; price += 7) {
            BigDecimal fills = OrderBookPricing.amountForNote(new BigDecimal("1234567.88"), price)
                    .add(OrderBookPricing.amountForNote(maxOutstanding, price))
                    .add(OrderBookPricing.amountForNote(new BigDecimal("1.01"), price));
            assertThat(fills).isLessThanOrEqualTo(hold);
        }
    }
}
