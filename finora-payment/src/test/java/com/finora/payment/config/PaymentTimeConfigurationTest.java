package com.finora.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
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
    void earlyMorningInVietnamIsStillTheVietnameseBusinessDate() {
        // 03:00 ngày 17/10 giờ Việt Nam: ngày trả nợ và tiền tố mã đơn ZaloPay phải là 17/10, không phải ngày UTC 16/10.
        Clock clock = Clock.fixed(Instant.parse("2026-10-16T20:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(DateTimeFormatter.ofPattern("yyMMdd").withZone(clock.getZone()).format(clock.instant()))
                .isEqualTo("261017");
    }
}
