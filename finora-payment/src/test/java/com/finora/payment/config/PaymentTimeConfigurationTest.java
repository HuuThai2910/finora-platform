package com.finora.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

class PaymentTimeConfigurationTest {

    @Test
    void businessClockUsesVietnamZone() {
        assertThat(PaymentTimeConfiguration.businessClock("Asia/Ho_Chi_Minh").getZone())
                .isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    @Test
    void e2eClockOffsetMovesTheBusinessDateOnlyWithMockMoneyProviders() {
        Clock business = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        Clock shifted = PaymentTimeConfiguration.e2eClock(business, Duration.ofDays(31), "mock", "mock");

        assertThat(LocalDate.now(shifted)).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(PaymentTimeConfiguration.e2eClock(business, Duration.ZERO, "zalopay", "zalopay"))
                .isSameAs(business);
        // Đồng hồ giả không bao giờ chạy cùng provider tiền thật.
        assertThatThrownBy(() -> PaymentTimeConfiguration.e2eClock(business, Duration.ofDays(31), "mock", "zalopay"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void earlyMorningInVietnamIsStillTheVietnameseBusinessDate() {
        // 03:00 ngày 17/10 giờ Việt Nam: ngày trả nợ và tiền tố mã đơn ZaloPay phải là 17/10, không phải ngày UTC 16/10.
        Clock clock = Clock.fixed(Instant.parse("2026-10-16T20:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(DateTimeFormatter.ofPattern("yyMMdd").withZone(clock.getZone()).format(clock.instant()))
                .isEqualTo("261017");
    }
}
