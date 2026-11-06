package com.finora.notification.service.preference;

import com.finora.notification.dto.request.UpdateNotificationPreferencesRequest;
import com.finora.notification.dto.response.NotificationPreferencesResponse;

/**
 * Tùy chọn push theo nhóm của chính người dùng (D63). Thông báo trong app luôn bật; không có dòng nghĩa là push bật.
 */
public interface NotificationPreferenceService {

    NotificationPreferencesResponse get(String userId);

    /** Ghi các nhóm có trong request (nhóm khác giữ nguyên); upsert nguyên tử nên lưu hai lần đồng thời không lỗi. */
    NotificationPreferencesResponse update(String userId, UpdateNotificationPreferencesRequest request);
}
