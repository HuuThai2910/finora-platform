package com.finora.notification.service.reminder;

/**
 * Worker nhắc kỳ đến hạn theo lịch giờ Việt Nam; chỉ bật khi {@code NOTIFICATION_DUE_REMINDER_ENABLED=true} (D64).
 */
public interface DueReminderWorker {

    /** Lượt theo cron {@code finora.notification.due-reminder.cron}: nhắc cho ngày nghiệp vụ hiện tại. */
    void scheduled();
}
