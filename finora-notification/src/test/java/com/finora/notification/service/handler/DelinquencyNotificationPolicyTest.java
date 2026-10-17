package com.finora.notification.service.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.notification.service.template.NotificationTemplate;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** D61: chỉ báo khi mở/đóng đợt quá hạn hoặc tăng nhóm nợ, không báo mỗi ngày DPD tăng. */
class DelinquencyNotificationPolicyTest {

    @ParameterizedTest(name = "DPD {0}->{1}, nhóm {2}->{3} => {4}")
    @CsvSource({
            "0, 1, 1, 1, LOAN_OVERDUE",
            "1, 2, 1, 1, NONE",
            "9, 10, 1, 2, OVERDUE_STAGE_CHANGED",
            "10, 11, 2, 2, NONE",
            "90, 91, 2, 3, LOAN_DEFAULTED",
            "91, 92, 3, 3, NONE",
            "180, 181, 3, 4, OVERDUE_STAGE_CHANGED",
            "360, 361, 4, 5, OVERDUE_STAGE_CHANGED",
            // D29: trả bớt khi đang nợ xấu không hạ nhóm, cũng không phải mốc mới.
            "95, 50, 3, 3, NONE",
            "50, 0, 3, 1, OVERDUE_CURED",
            "3, 0, 1, 1, OVERDUE_CURED",
            // Job cuối ngày của Fineract không chạy nhiều ngày: DPD nhảy thẳng, chỉ một thông báo đúng mức nặng nhất.
            "0, 15, 1, 2, LOAN_OVERDUE",
            "0, 95, 1, 3, LOAN_DEFAULTED",
            "0, 0, 1, 1, NONE"
    })
    void decidesOnlyOnEpisodeOrStageChange(int previousDpd, int dpd, int previousGroup, int group, String expected) {
        Optional<NotificationTemplate> decision = DelinquencyNotificationPolicy.decide(previousDpd, dpd, previousGroup,
                group);

        if ("NONE".equals(expected)) {
            assertThat(decision).isEmpty();
        } else {
            assertThat(decision).contains(NotificationTemplate.valueOf(expected));
        }
    }

    @Test
    void aHundredDaysOverdueProducesThreeNotificationsNotAHundred() {
        long notifications = IntStream.rangeClosed(1, 100)
                .mapToObj(day -> DelinquencyNotificationPolicy.decide(day - 1, day,
                        groupOf(day - 1), groupOf(day)))
                .filter(Optional::isPresent)
                .count();

        // Ngày 1 (mở đợt), ngày 10 (nhóm 2), ngày 91 (nợ xấu).
        assertThat(notifications).isEqualTo(3);
    }

    private static int groupOf(int dpd) {
        if (dpd <= 9) return 1;
        if (dpd <= 90) return 2;
        if (dpd <= 180) return 3;
        if (dpd <= 360) return 4;
        return 5;
    }
}
