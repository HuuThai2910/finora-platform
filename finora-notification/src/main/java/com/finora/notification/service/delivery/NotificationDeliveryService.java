package com.finora.notification.service.delivery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lưu thông báo trong app và xếp lịch push trong transaction của caller (consumer/worker), không gọi mạng (F09).
 */
public interface NotificationDeliveryService {

    /** @return số thông báo mới được tạo (thông báo đã tồn tại từ lần xử lý trước không tính) */
    int deliver(UUID sourceEventId, Instant occurredAt, List<NotificationDraft> drafts);
}
