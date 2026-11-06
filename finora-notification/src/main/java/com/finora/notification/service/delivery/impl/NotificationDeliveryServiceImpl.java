package com.finora.notification.service.delivery.impl;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.config.PushProviderType;
import com.finora.notification.domain.entity.InAppNotification;
import com.finora.notification.domain.entity.NotificationPreference;
import com.finora.notification.domain.entity.PushDelivery;
import com.finora.notification.domain.entity.PushDevice;
import com.finora.notification.domain.enums.NotificationCategory;
import com.finora.notification.domain.enums.PushDeviceStatus;
import com.finora.notification.repository.InAppNotificationRepository;
import com.finora.notification.repository.NotificationPreferenceRepository;
import com.finora.notification.repository.PushDeliveryRepository;
import com.finora.notification.repository.PushDeviceRepository;
import com.finora.notification.service.delivery.NotificationDeliveryService;
import com.finora.notification.service.delivery.NotificationDraft;
import com.finora.notification.service.delivery.QuietHoursPolicy;
import com.finora.notification.service.template.NotificationTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lưu thông báo trong app và xếp lịch push trong transaction của caller (consumer/worker), không gọi mạng (F09).
 * <p>
 * Idempotency: hộp thư unique {@code (sourceEventId, recipientId, type)}; push unique {@code (notificationId, deviceId)},
 * tức mỗi event chỉ tạo một push cho mỗi thiết bị của mỗi người nhận. Thông báo đã có thì bỏ qua cả push của nó.
 * <p>
 * Push chỉ được xếp khi: provider khác {@code disabled}, event còn mới ({@code maxEventAge}, chặn push tràn khi đọc dồn
 * Kafka từ đầu), người nhận không tắt nhóm đó và có thiết bị đang hoạt động. Preference và thiết bị của mọi người nhận
 * được đọc bằng truy vấn theo cả lô, không theo từng người (tránh N+1 khi fan-out cho nhiều nhà đầu tư).
 */
@Service
@RequiredArgsConstructor
public class NotificationDeliveryServiceImpl implements NotificationDeliveryService {

    private final InAppNotificationRepository inAppNotificationRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final PushDeviceRepository pushDeviceRepository;
    private final PushDeliveryRepository pushDeliveryRepository;
    private final QuietHoursPolicy quietHoursPolicy;
    private final NotificationProperties notificationProperties;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deliver(UUID sourceEventId, Instant occurredAt, List<NotificationDraft> drafts) {
        Instant now = clock.instant();
        // Một câu cho cả lô (prefix của unique (source_event_id, recipient_id, type)); chỉ có dòng khi event từng được
        // xử lý dở hoặc nhắc lịch chạy lại trong ngày.
        Set<String> existing = inAppNotificationRepository.findBySourceEventId(sourceEventId).stream()
                .map(value -> value.getRecipientId() + '\u0000' + value.getType())
                .collect(Collectors.toSet());
        List<PushCandidate> pushCandidates = new ArrayList<>();
        int created = 0;
        for (NotificationDraft draft : drafts) {
            if (draft.recipientId() == null || draft.recipientId().isBlank()) continue;
            if (!existing.add(draft.recipientId() + '\u0000' + draft.type())) continue;
            NotificationTemplate template = draft.template();
            InAppNotification notification = inAppNotificationRepository.save(InAppNotification.create(sourceEventId,
                    draft.recipientId(), draft.type(), template.category(), template.version(), template.title(),
                    template.renderMessage(draft.values()), draft.businessReference(), draft.pushRequested(),
                    occurredAt, now));
            created++;
            if (draft.pushRequested()) pushCandidates.add(new PushCandidate(notification, template));
        }
        schedulePushes(pushCandidates, occurredAt, now);
        return created;
    }

    private void schedulePushes(List<PushCandidate> candidates, Instant occurredAt, Instant now) {
        if (candidates.isEmpty() || notificationProperties.push().provider() == PushProviderType.DISABLED) return;
        if (occurredAt.isBefore(now.minus(notificationProperties.push().maxEventAge()))) return;
        Set<String> recipients = candidates.stream().map(candidate -> candidate.notification().getRecipientId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<PushDevice>> devicesByUser = pushDeviceRepository.findByUserIdInAndStatus(recipients,
                        PushDeviceStatus.ACTIVE).stream()
                .collect(Collectors.groupingBy(PushDevice::getUserId));
        if (devicesByUser.isEmpty()) return;
        Map<NotificationCategory, Set<String>> optedOut = optedOut(candidates, recipients);
        List<PushDelivery> scheduled = new ArrayList<>();
        for (PushCandidate candidate : candidates) {
            InAppNotification notification = candidate.notification();
            if (optedOut.getOrDefault(notification.getCategory(), Set.of()).contains(notification.getRecipientId())) {
                continue;
            }
            Instant firstAttempt = quietHoursPolicy.earliestSendTime(notification.getCategory(), now);
            for (PushDevice device : devicesByUser.getOrDefault(notification.getRecipientId(), List.of())) {
                scheduled.add(PushDelivery.pending(notification, device, candidate.template().pushTitle(),
                        candidate.template().pushBody(), firstAttempt, now));
            }
        }
        pushDeliveryRepository.saveAll(scheduled);
    }

    /** Một lô thường chỉ có một nhóm; đọc preference theo từng nhóm có trong lô (thường đúng một câu truy vấn). */
    private Map<NotificationCategory, Set<String>> optedOut(List<PushCandidate> candidates, Set<String> recipients) {
        Set<NotificationCategory> categories = candidates.stream()
                .map(candidate -> candidate.notification().getCategory())
                .collect(Collectors.toSet());
        Map<NotificationCategory, Set<String>> result = new EnumMap<>(NotificationCategory.class);
        for (NotificationCategory category : categories) {
            result.put(category, notificationPreferenceRepository.findByUserIdInAndCategory(recipients, category)
                    .stream()
                    .filter(preference -> !preference.isPushEnabled())
                    .map(NotificationPreference::getUserId)
                    .collect(Collectors.toSet()));
        }
        return result;
    }

    private record PushCandidate(InAppNotification notification, NotificationTemplate template) {}
}
