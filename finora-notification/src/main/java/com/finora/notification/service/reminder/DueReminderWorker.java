package com.finora.notification.service.reminder;

import com.finora.notification.config.NotificationProperties;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chạy nhắc lịch mỗi giờ từ 08:05 tới 20:05 giờ Việt Nam: lượt 08:05 tạo lời nhắc, các lượt sau chỉ bù khi service vừa
 * khởi động lại (idempotent). Không chạy ban đêm. Chỉ bật khi {@code NOTIFICATION_DUE_REMINDER_ENABLED=true} (D64).
 */
@Component
@RequiredArgsConstructor
public class DueReminderWorker {

    private final DueReminderService reminders;
    private final NotificationProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${finora.notification.due-reminder.cron:0 5 8-20 * * *}",
            zone = "${finora.notification.business-time-zone:Asia/Ho_Chi_Minh}")
    public void scheduled() {
        if (!properties.dueReminder().enabled()) return;
        reminders.remind(LocalDate.ofInstant(clock.instant(), properties.businessTimeZone()));
    }
}
