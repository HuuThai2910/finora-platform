package com.finora.loan.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LoanTimeConfigTest {

    @Test
    void businessClockUsesConfiguredVietnamZone() {
        var clock = LoanTimeConfig.businessClock("Asia/Ho_Chi_Minh");

        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
        // Instant tuyệt đối không đổi; chỉ ngày nghiệp vụ lấy theo giờ Việt Nam.
        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    @Test
    void e2eClockOffsetOnlyRunsWithMockSignaturesOnTheLocalProfile() {
        Clock business = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        assertThat(LocalDate.now(LoanTimeConfig.e2eClock(business, Duration.ofDays(101), "MOCK", true)))
                .isEqualTo(LocalDate.of(2027, 1, 26));
        assertThat(LoanTimeConfig.e2eClock(business, Duration.ZERO, "VNPT_SMART_CA", false)).isSameAs(business);
        // Chữ ký số thật hoặc môi trường không phải local không bao giờ chạy đồng hồ giả.
        assertThatThrownBy(() -> LoanTimeConfig.e2eClock(business, Duration.ofDays(101), "VNPT_SMART_CA", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LoanTimeConfig.e2eClock(business, Duration.ofDays(101), "MOCK", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidZoneFailsFastAtStartup() {
        assertThatThrownBy(() -> LoanTimeConfig.businessClock("Mars/Base"))
                .isInstanceOf(DateTimeException.class);
    }
}
