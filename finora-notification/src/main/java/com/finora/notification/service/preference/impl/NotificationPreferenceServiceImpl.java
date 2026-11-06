package com.finora.notification.service.preference.impl;

import com.finora.common.exception.BusinessException;
import com.finora.notification.domain.entity.NotificationPreference;
import com.finora.notification.domain.enums.NotificationCategory;
import com.finora.notification.dto.request.UpdateNotificationPreferencesRequest;
import com.finora.notification.dto.response.NotificationPreferencesResponse;
import com.finora.notification.repository.NotificationPreferenceRepository;
import com.finora.notification.service.preference.NotificationPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tùy chọn push theo nhóm của chính người dùng (D63). Thông báo trong app luôn bật; không có dòng nghĩa là push bật.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public NotificationPreferencesResponse get(String userId) {
        Map<NotificationCategory, Boolean> stored = new EnumMap<>(NotificationCategory.class);
        for (NotificationPreference preference : notificationPreferenceRepository.findByUserId(userId)) {
            stored.put(preference.getCategory(), preference.isPushEnabled());
        }
        return new NotificationPreferencesResponse(Arrays.stream(NotificationCategory.values())
                .map(category -> new NotificationPreferencesResponse.Item(category,
                        stored.getOrDefault(category, true), true))
                .toList());
    }

    @Override
    @Transactional
    public NotificationPreferencesResponse update(String userId, UpdateNotificationPreferencesRequest request) {
        Set<NotificationCategory> seen = new HashSet<>();
        Instant now = clock.instant();
        for (UpdateNotificationPreferencesRequest.Item item : request.preferences()) {
            if (!seen.add(item.category())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTIFICATION_PREFERENCE_DUPLICATED",
                        "Mỗi nhóm thông báo chỉ được gửi một lần");
            }
            notificationPreferenceRepository.upsert(userId, item.category().name(), item.pushEnabled(), now);
        }
        return get(userId);
    }
}
