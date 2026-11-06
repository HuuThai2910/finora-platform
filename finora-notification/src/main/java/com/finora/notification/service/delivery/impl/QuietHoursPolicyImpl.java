package com.finora.notification.service.delivery.impl;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.domain.enums.NotificationCategory;
import com.finora.notification.service.delivery.QuietHoursPolicy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Giờ nghỉ của push (D62): job cuối ngày của Fineract chạy lúc 00:01 nên event quá hạn tới lúc nửa đêm. Push không gấp
 * được hẹn tới hết giờ nghỉ (mặc định 22:00–07:00 giờ Việt Nam); thông báo trong app vẫn có ngay.
 */
@Component
public class QuietHoursPolicyImpl implements QuietHoursPolicy {

    private final NotificationProperties.QuietHours quietHours;
    private final ZoneId zone;

    public QuietHoursPolicyImpl(NotificationProperties properties) {
        this.quietHours = properties.push().quietHours();
        this.zone = properties.businessTimeZone();
    }

    @Override
    public Instant earliestSendTime(NotificationCategory category, Instant now) {
        if (!quietHours.enabled() || category.deliversDuringQuietHours()) return now;
        ZonedDateTime local = now.atZone(zone);
        LocalTime time = local.toLocalTime();
        LocalTime start = quietHours.start();
        LocalTime end = quietHours.end();
        boolean overnight = start.isAfter(end);
        boolean quiet = overnight
                ? !time.isBefore(start) || time.isBefore(end)
                : !time.isBefore(start) && time.isBefore(end);
        if (!quiet) return now;
        ZonedDateTime resume = local.with(end);
        if (!resume.isAfter(local)) resume = resume.plusDays(1);
        return resume.toInstant();
    }
}
