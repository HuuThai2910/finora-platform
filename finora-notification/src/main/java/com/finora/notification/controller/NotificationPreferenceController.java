package com.finora.notification.controller;

import com.finora.common.security.SecurityUtils;
import com.finora.notification.dto.request.UpdateNotificationPreferencesRequest;
import com.finora.notification.dto.response.NotificationPreferencesResponse;
import com.finora.notification.service.preference.NotificationPreferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bật/tắt push theo nhóm thông báo của chính người dùng (D63). */
@RestController
@RequestMapping("/api/v1/notifications/preferences")
@RequiredArgsConstructor
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferences;

    @GetMapping
    public NotificationPreferencesResponse get() {
        return preferences.get(SecurityUtils.getCurrentUserId());
    }

    @PutMapping
    public NotificationPreferencesResponse update(@Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return preferences.update(SecurityUtils.getCurrentUserId(), request);
    }
}
