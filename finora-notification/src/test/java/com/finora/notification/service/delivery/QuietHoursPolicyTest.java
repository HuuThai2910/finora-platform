package com.finora.notification.service.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.domain.NotificationCategory;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** D62: push không gấp không gửi trong 22:00–07:00 giờ Việt Nam; thông báo trong app không bị ảnh hưởng. */
class QuietHoursPolicyTest {

    private final QuietHoursPolicy policy = new QuietHoursPolicy(properties(true));

    @Test
    void lateEveningPushWaitsUntilSevenNextMorning() {
        // 23:00 ngày 16/10 giờ Việt Nam = 16:00Z.
        Instant sent = policy.earliestSendTime(NotificationCategory.DELINQUENCY, Instant.parse("2026-10-16T16:00:00Z"));

        assertThat(sent).isEqualTo(Instant.parse("2026-10-17T00:00:00Z"));
    }

    @Test
    void justAfterMidnightPushWaitsUntilSevenTheSameMorning() {
        // 00:05 giờ Việt Nam: lúc job cuối ngày Fineract vừa đổi DPD.
        Instant sent = policy.earliestSendTime(NotificationCategory.DELINQUENCY, Instant.parse("2026-10-16T17:05:00Z"));

        assertThat(sent).isEqualTo(Instant.parse("2026-10-17T00:00:00Z"));
    }

    @Test
    void daytimePushIsSentImmediately() {
        Instant now = Instant.parse("2026-10-17T05:00:00Z");

        assertThat(policy.earliestSendTime(NotificationCategory.REPAYMENT, now)).isEqualTo(now);
    }

    @Test
    void withdrawalResultIsSentAtNightBecauseTheUserJustAskedForIt() {
        Instant now = Instant.parse("2026-10-16T16:00:00Z");

        assertThat(policy.earliestSendTime(NotificationCategory.WALLET, now)).isEqualTo(now);
    }

    @Test
    void disabledQuietHoursNeverDelay() {
        Instant now = Instant.parse("2026-10-16T16:00:00Z");

        assertThat(new QuietHoursPolicy(properties(false)).earliestSendTime(NotificationCategory.DELINQUENCY, now))
                .isEqualTo(now);
    }

    private static NotificationProperties properties(boolean quietHours) {
        NotificationProperties.Push push = new NotificationProperties.Push(null, 0, 0, null, null, null, null, null,
                0, 0, null, null, 0,
                new NotificationProperties.QuietHours(quietHours, LocalTime.of(22, 0), LocalTime.of(7, 0)), null);
        return new NotificationProperties(ZoneId.of("Asia/Ho_Chi_Minh"), null, push, null, null);
    }
}
