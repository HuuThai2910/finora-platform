package com.finora.notification.dto.request;

import com.finora.notification.domain.NotificationCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Bật/tắt push cho một hoặc nhiều nhóm; nhóm không có trong request giữ nguyên. */
public record UpdateNotificationPreferencesRequest(
        @NotEmpty(message = "Cần ít nhất một nhóm thông báo")
        @Size(max = 20, message = "Quá nhiều nhóm thông báo")
        List<@Valid @NotNull(message = "Thiếu nhóm thông báo") Item> preferences
) {
    public record Item(
            @NotNull(message = "Thiếu nhóm thông báo") NotificationCategory category,
            @NotNull(message = "Thiếu lựa chọn push") Boolean pushEnabled
    ) {}
}
