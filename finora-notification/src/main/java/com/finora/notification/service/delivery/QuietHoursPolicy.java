package com.finora.notification.service.delivery;

import com.finora.notification.domain.enums.NotificationCategory;

import java.time.Instant;

/**
 * Giờ nghỉ của push (D62): push không gấp được hẹn tới hết giờ nghỉ (mặc định 22:00–07:00 giờ Việt Nam); thông báo
 * trong app vẫn có ngay.
 */
public interface QuietHoursPolicy {

    /** Thời điểm sớm nhất được gửi push của nhóm này nếu nó được tạo lúc {@code now}. */
    Instant earliestSendTime(NotificationCategory category, Instant now);
}
