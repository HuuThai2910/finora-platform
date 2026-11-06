package com.finora.notification.service.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.notification.dto.response.InAppNotificationResponse;
import com.finora.notification.repository.InAppNotificationRepository;
import com.finora.notification.service.InAppNotificationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Hộp thư trong app của người đang đăng nhập; người nhận luôn lấy từ JWT, không nhận từ request. */
@Service
@RequiredArgsConstructor
public class InAppNotificationQueryServiceImpl implements InAppNotificationQueryService {
    private final InAppNotificationRepository inAppNotificationRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<InAppNotificationResponse> list(int limit) {
        String userId = SecurityUtils.getCurrentUserId();
        return inAppNotificationRepository.findByRecipientIdOrderByOccurredAtDescIdDesc(
                        userId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100))).stream()
                .map(InAppNotificationResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount() {
        return inAppNotificationRepository.countByRecipientIdAndReadAtIsNull(SecurityUtils.getCurrentUserId());
    }

    @Override
    @Transactional
    public void markRead(UUID id) {
        inAppNotificationRepository.findByIdAndRecipientId(id, SecurityUtils.getCurrentUserId())
                .ifPresent(value -> value.markRead(clock.instant()));
    }

    @Override
    @Transactional
    public int markAllRead() {
        return inAppNotificationRepository.markAllRead(SecurityUtils.getCurrentUserId(), clock.instant());
    }
}
