package com.finora.notification.dto.response;

import com.finora.notification.domain.NotificationCategory;
import java.util.List;

/**
 * Lựa chọn của người dùng cho mọi nhóm, theo thứ tự cố định. {@code inAppEnabled} luôn {@code true}: hộp thư là bản
 * ghi đầy đủ, chỉ push mới tắt được. App tự đặt nhãn tiếng Việt theo {@code category}.
 */
public record NotificationPreferencesResponse(List<Item> categories) {
    public record Item(NotificationCategory category, boolean pushEnabled, boolean inAppEnabled) {}
}
