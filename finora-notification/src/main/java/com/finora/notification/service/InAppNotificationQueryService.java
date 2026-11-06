package com.finora.notification.service;

import com.finora.notification.dto.response.InAppNotificationResponse;

import java.util.List;
import java.util.UUID;

/** Hộp thư trong app của người đang đăng nhập; người nhận luôn lấy từ JWT, không nhận từ request. */
public interface InAppNotificationQueryService {

    List<InAppNotificationResponse> list(int limit);

    long unreadCount();

    void markRead(UUID id);

    /** Một câu UPDATE cho mọi tin chưa đọc, thay cho việc app gọi {@code /{id}/read} từng tin. */
    int markAllRead();
}
