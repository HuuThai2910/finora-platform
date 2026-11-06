package com.finora.notification.service.reminder.impl;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.service.reminder.DueReminderService;
import com.finora.notification.service.reminder.DueReminderWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Chạy nhắc lịch mỗi giờ từ 08:05 tới 20:05 giờ Việt Nam: lượt 08:05 tạo lời nhắc, các lượt sau chỉ bù khi service vừa
 * khởi động lại (idempotent). Không chạy ban đêm. Chỉ bật khi {@code NOTIFICATION_DUE_REMINDER_ENABLED=true} (D64).
 */
@Component
@RequiredArgsConstructor
public class DueReminderWorkerImpl implements DueReminderWorker {

    private final DueReminderService dueReminderService;
    private final NotificationProperties notificationProperties;
    private final Clock clock;

    @Override
    @Scheduled(cron = "${finora.notification.due-reminder.cron:0 5 8-20 * * *}",
            zone = "${finora.notification.business-time-zone:Asia/Ho_Chi_Minh}")
    public void scheduled() {
        if (!notificationProperties.dueReminder().enabled()) return;
        dueReminderService.remind(LocalDate.ofInstant(clock.instant(), notificationProperties.businessTimeZone()));
    }
}
