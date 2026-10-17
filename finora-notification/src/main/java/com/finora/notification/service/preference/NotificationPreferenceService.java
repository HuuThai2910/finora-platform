package com.finora.notification.service.preference;

import com.finora.common.exception.BusinessException;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationPreference;
import com.finora.notification.dto.request.UpdateNotificationPreferencesRequest;
import com.finora.notification.dto.response.NotificationPreferencesResponse;
import com.finora.notification.repository.NotificationPreferenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tùy chọn push theo nhóm của chính người dùng (D63). Thông báo trong app luôn bật; không có dòng nghĩa là push bật.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferences;
    private final Clock clock;

    @Transactional(readOnly = true)
    public NotificationPreferencesResponse get(String userId) {
        Map<NotificationCategory, Boolean> stored = new EnumMap<>(NotificationCategory.class);
        for (NotificationPreference preference : preferences.findByUserId(userId)) {
            stored.put(preference.getCategory(), preference.isPushEnabled());
        }
        return new NotificationPreferencesResponse(Arrays.stream(NotificationCategory.values())
                .map(category -> new NotificationPreferencesResponse.Item(category,
                        stored.getOrDefault(category, true), true))
                .toList());
    }

    /** Ghi các nhóm có trong request (nhóm khác giữ nguyên); upsert nguyên tử nên lưu hai lần đồng thời không lỗi. */
    @Transactional
    public NotificationPreferencesResponse update(String userId, UpdateNotificationPreferencesRequest request) {
        Set<NotificationCategory> seen = new HashSet<>();
        Instant now = clock.instant();
        for (UpdateNotificationPreferencesRequest.Item item : request.preferences()) {
            if (!seen.add(item.category())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTIFICATION_PREFERENCE_DUPLICATED",
                        "Mỗi nhóm thông báo chỉ được gửi một lần");
            }
            preferences.upsert(userId, item.category().name(), item.pushEnabled(), now);
        }
        return get(userId);
    }
}
