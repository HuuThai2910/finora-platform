package com.finora.loan.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DateTimeException;
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
    void invalidZoneFailsFastAtStartup() {
        assertThatThrownBy(() -> LoanTimeConfig.businessClock("Mars/Base"))
                .isInstanceOf(DateTimeException.class);
    }
}
