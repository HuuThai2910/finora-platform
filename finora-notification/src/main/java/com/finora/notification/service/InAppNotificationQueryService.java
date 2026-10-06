package com.finora.notification.service;

import com.finora.common.security.SecurityUtils;
import com.finora.notification.dto.response.InAppNotificationResponse;
import com.finora.notification.repository.InAppNotificationRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InAppNotificationQueryService {
    private final InAppNotificationRepository repository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<InAppNotificationResponse> list(int limit) {
        String userId = SecurityUtils.getCurrentUserId();
        return repository.findByRecipientIdOrderByOccurredAtDescIdDesc(
                        userId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100))).stream()
                .map(InAppNotificationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return repository.countByRecipientIdAndReadAtIsNull(SecurityUtils.getCurrentUserId());
    }

    @Transactional
    public void markRead(UUID id) {
        repository.findByIdAndRecipientId(id, SecurityUtils.getCurrentUserId())
                .ifPresent(value -> value.markRead(clock.instant()));
    }
}
