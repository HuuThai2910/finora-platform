package com.finora.investment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.investment.domain.autoinvest.AutoInvestConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AutoInvestConfigTest {

    private static final Instant T0 = Instant.parse("2026-09-30T08:00:00Z");

    private AutoInvestConfig enabledConfig() {
        AutoInvestConfig config = AutoInvestConfig.create("INV-1", T0);
        config.update(true, List.of("A", "B"), new BigDecimal("15"), 18,
                new BigDecimal("4000000"), T0);
        return config;
    }

    @Test
    @DisplayName("Khớp khi hạng, lãi suất, kỳ hạn đều đạt và khoản mở sau lúc bật")
    void acceptsMatchingListing() {
        AutoInvestConfig config = enabledConfig();

        assertThat(config.accepts("A", new BigDecimal("15.0000"), 18, T0.plusSeconds(60))).isTrue();
        assertThat(config.accepts("C", new BigDecimal("20"), 12, T0.plusSeconds(60))).isFalse();
        assertThat(config.accepts("A", new BigDecimal("14.9999"), 12, T0.plusSeconds(60))).isFalse();
        assertThat(config.accepts("B", new BigDecimal("16"), 24, T0.plusSeconds(60))).isFalse();
    }

    @Test
    @DisplayName("Không quét ngược khoản đã mở trước lúc bật Auto-Invest")
    void ignoresListingsOpenedBeforeEnabling() {
        AutoInvestConfig config = enabledConfig();

        assertThat(config.accepts("A", new BigDecimal("16"), 12, T0.minusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("Tắt thì không khớp gì")
    void disabledAcceptsNothing() {
        AutoInvestConfig config = enabledConfig();
        config.update(false, List.of("A"), BigDecimal.ZERO, 120, new BigDecimal("1000000"), T0.plusSeconds(5));

        assertThat(config.accepts("A", new BigDecimal("16"), 12, T0.plusSeconds(60))).isFalse();
    }

    @Test
    @DisplayName("Lưu lại khi đang bật giữ chỗ trong hàng; tắt rồi bật lại thì xuống cuối hàng")
    void enabledAtOnlyMovesOnReEnable() {
        AutoInvestConfig config = enabledConfig();

        config.update(true, List.of("A"), new BigDecimal("10"), 12, new BigDecimal("2000000"), T0.plusSeconds(10));
        assertThat(config.getEnabledAt()).isEqualTo(T0);

        config.update(false, List.of("A"), new BigDecimal("10"), 12, new BigDecimal("2000000"), T0.plusSeconds(20));
        config.update(true, List.of("A"), new BigDecimal("10"), 12, new BigDecimal("2000000"), T0.plusSeconds(30));
        assertThat(config.getEnabledAt()).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    @DisplayName("Số tiền không vượt mức mỗi khoản, vốn còn lại và làm tròn xuống mệnh giá Note")
    void orderAmountRoundsDownToDenomination() {
        BigDecimal denomination = new BigDecimal("1000000.00");

        assertThat(AutoInvestConfig.orderAmount(new BigDecimal("4500000"), new BigDecimal("10000000"), denomination))
                .isEqualByComparingTo("4000000");
        assertThat(AutoInvestConfig.orderAmount(new BigDecimal("4000000"), new BigDecimal("2500000"), denomination))
                .isEqualByComparingTo("2000000");
        assertThat(AutoInvestConfig.orderAmount(new BigDecimal("4000000"), new BigDecimal("500000"), denomination))
                .isEqualByComparingTo("0");
    }
}
