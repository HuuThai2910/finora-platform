package com.finora.notification.service.push.impl;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.domain.entity.InAppNotification;
import com.finora.notification.domain.entity.PushDelivery;
import com.finora.notification.domain.entity.PushDevice;
import com.finora.notification.domain.enums.DeviceRevocationReason;
import com.finora.notification.domain.enums.PushDeliveryStatus;
import com.finora.notification.integration.push.contract.PushMessage;
import com.finora.notification.integration.push.contract.PushOutcome;
import com.finora.notification.integration.push.contract.PushReceipt;
import com.finora.notification.integration.push.contract.PushTicket;
import com.finora.notification.repository.InAppNotificationRepository;
import com.finora.notification.repository.PushDeliveryRepository;
import com.finora.notification.repository.PushDeviceRepository;
import com.finora.notification.service.push.PushDispatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Trạng thái push trong database; provider được gọi bởi worker ở giữa hai transaction (D62).
 * <pre>
 * Transaction 1 (claimDue): khóa lô đến hạn bằng SKIP LOCKED → SENDING, attempt+1, lease → commit
 * Ngoài transaction       : PushSender.send(lô) — không giữ khóa/kết nối DB khi chờ mạng
 * Transaction 2 (recordTickets): khóa lại các dòng, chỉ ghi khi còn đúng claim token (attempt) → SENT/PENDING/DEAD/FAILED
 * </pre>
 * Worker chết sau bước 1: lease hết hạn, dòng SENDING được nhận lại ở lượt sau (có thể gửi trùng một lần: at-least-once).
 * Kết quả muộn của lượt cũ bị bỏ qua vì claim token đã đổi.
 */
@Slf4j
@Service
public class PushDispatchServiceImpl implements PushDispatchService {

    private final PushDeliveryRepository pushDeliveryRepository;
    private final PushDeviceRepository pushDeviceRepository;
    private final InAppNotificationRepository inAppNotificationRepository;
    private final NotificationProperties.Push policy;
    private final Clock clock;

    public PushDispatchServiceImpl(PushDeliveryRepository pushDeliveryRepository,
            PushDeviceRepository pushDeviceRepository, InAppNotificationRepository inAppNotificationRepository,
            NotificationProperties properties, Clock clock) {
        this.pushDeliveryRepository = pushDeliveryRepository;
        this.pushDeviceRepository = pushDeviceRepository;
        this.inAppNotificationRepository = inAppNotificationRepository;
        this.policy = properties.push();
        this.clock = clock;
    }

    @Override
    @Transactional
    public List<ClaimedPush> claimDue(String providerCode) {
        Instant now = clock.instant();
        List<Long> ids = pushDeliveryRepository.lockDueForDispatch(now, policy.batchSize());
        if (ids.isEmpty()) return List.of();
        Map<Long, PushDelivery> rows = byId(pushDeliveryRepository.findAllById(ids), PushDelivery::getId);
        Map<UUID, PushDevice> devicesById = byId(pushDeviceRepository.findAllById(rows.values().stream()
                .map(PushDelivery::getDeviceId).collect(Collectors.toSet())), PushDevice::getId);
        Map<UUID, InAppNotification> notificationsById = byId(inAppNotificationRepository.findAllById(
                rows.values().stream().map(PushDelivery::getNotificationId).collect(Collectors.toSet())),
                InAppNotification::getId);
        List<ClaimedPush> claimed = new ArrayList<>();
        for (Long id : ids) {
            PushDelivery row = rows.get(id);
            if (row == null) continue;
            PushDevice device = devicesById.get(row.getDeviceId());
            if (device == null || !device.isActive()) {
                // Đăng xuất hoặc Expo đã báo DeviceNotRegistered sau khi xếp lịch: không gửi tới token đã thu hồi.
                row.skip("DEVICE_REVOKED", now);
                continue;
            }
            row.claim(providerCode, now.plus(policy.sendingLease()), now);
            InAppNotification notification = notificationsById.get(row.getNotificationId());
            Map<String, String> pushData = Map.of(
                    "notificationId", row.getNotificationId().toString(),
                    "type", notification == null ? "" : notification.getType(),
                    "category", row.getCategory().name());
            claimed.add(new ClaimedPush(row.getId(), row.getAttemptCount(), new PushMessage(row.getId(),
                    device.getToken(), device.tokenRef(), row.getTitle(), row.getBody(), pushData, policy.ttl())));
        }
        return claimed;
    }

    @Override
    @Transactional
    public TicketSummary recordTickets(List<ClaimedPush> claimed, List<PushTicket> tickets) {
        Instant now = clock.instant();
        Map<Long, PushDelivery> rows = byId(pushDeliveryRepository.findAllForUpdate(
                claimed.stream().map(ClaimedPush::deliveryId).toList()), PushDelivery::getId);
        Set<UUID> unregistered = new HashSet<>();
        int accepted = 0;
        int retried = 0;
        int failed = 0;
        int dead = 0;
        for (int index = 0; index < claimed.size(); index++) {
            ClaimedPush push = claimed.get(index);
            PushTicket ticket = index < tickets.size() ? tickets.get(index) : null;
            PushDelivery row = rows.get(push.deliveryId());
            if (row == null || !row.isClaimedBy(push.attempt())) {
                log.warn("Bỏ qua kết quả push của lượt claim cũ: deliveryId={}, attempt={}",
                        push.deliveryId(), push.attempt());
                continue;
            }
            if (ticket == null) {
                ticket = PushTicket.failed(PushOutcome.RETRYABLE_FAILURE,
                        "PUSH_TICKET_MISSING");
            }
            switch (ticket.outcome()) {
                case ACCEPTED -> {
                    row.accepted(ticket.ticketId(), now.plus(policy.receiptDelay()), now);
                    accepted++;
                }
                case DEVICE_NOT_REGISTERED -> {
                    row.failPermanently(ticket.errorCode(), now);
                    unregistered.add(row.getDeviceId());
                    failed++;
                }
                case PERMANENT_FAILURE -> {
                    row.failPermanently(ticket.errorCode(), now);
                    failed++;
                }
                case RETRYABLE_FAILURE -> {
                    row.failRetryable(ticket.errorCode(), now.plus(backoff(row.getAttemptCount())),
                            policy.maxAttempts(), now);
                    if (row.getStatus() == PushDeliveryStatus.DEAD) {
                        dead++;
                        log.error("Push vào dead-letter sau {} lần thử: deliveryId={}, errorCode={}",
                                row.getAttemptCount(), row.getId(), ticket.errorCode());
                    } else {
                        retried++;
                    }
                }
            }
        }
        revokeUnregistered(unregistered, now);
        return new TicketSummary(accepted, retried, failed, dead);
    }

    @Override
    @Transactional
    public List<ReceiptCheck> claimReceiptChecks(String providerCode) {
        Instant now = clock.instant();
        List<Long> ids = pushDeliveryRepository.lockDueForReceipt(now, policy.receiptBatchSize());
        if (ids.isEmpty()) return List.of();
        List<ReceiptCheck> checks = new ArrayList<>();
        for (PushDelivery row : pushDeliveryRepository.findAllById(ids)) {
            if (row.getTicketId() == null || !providerCode.equals(row.getProvider())) {
                // Ticket của provider khác (đổi cấu hình giữa chừng) không hỏi được provider hiện tại.
                row.stopReceiptChecks(now);
                continue;
            }
            row.claimReceiptCheck(now.plus(policy.receiptRetryInterval()), now);
            checks.add(new ReceiptCheck(row.getId(), row.getTicketId()));
        }
        return checks;
    }

    @Override
    @Transactional
    public void recordReceipts(List<ReceiptCheck> checks, Map<String, PushReceipt> receipts) {
        if (checks.isEmpty()) return;
        Instant now = clock.instant();
        Map<Long, PushDelivery> rows = byId(pushDeliveryRepository.findAllForUpdate(
                checks.stream().map(ReceiptCheck::deliveryId).toList()), PushDelivery::getId);
        Set<UUID> unregistered = new HashSet<>();
        for (ReceiptCheck check : checks) {
            PushDelivery row = rows.get(check.deliveryId());
            if (row == null || row.getStatus() != PushDeliveryStatus.SENT
                    || !check.ticketId().equals(row.getTicketId())) continue;
            PushReceipt receipt = receipts.get(check.ticketId());
            if (receipt == null) {
                if (row.getReceiptCheckCount() >= policy.receiptMaxChecks()) row.stopReceiptChecks(now);
                continue;
            }
            switch (receipt.outcome()) {
                case ACCEPTED -> row.confirm(now);
                case DEVICE_NOT_REGISTERED -> {
                    row.failPermanently(receipt.errorCode(), now);
                    unregistered.add(row.getDeviceId());
                }
                case PERMANENT_FAILURE -> row.failPermanently(receipt.errorCode(), now);
                // APNs/FCM báo vượt tần suất: gửi lại có lùi dần trong giới hạn lượt.
                case RETRYABLE_FAILURE -> row.failRetryable(receipt.errorCode(),
                        now.plus(backoff(row.getAttemptCount())), policy.maxAttempts(), now);
            }
        }
        revokeUnregistered(unregistered, now);
    }

    /** Lùi dần: {@code retryBackoff × 2^(attempt-1)}, không quá {@code maximumBackoff}. */
    Duration backoff(int attempt) {
        long factor = 1L << Math.max(0, Math.min(attempt - 1, 20));
        Duration delay = policy.retryBackoff().multipliedBy(factor);
        return delay.compareTo(policy.maximumBackoff()) > 0 ? policy.maximumBackoff() : delay;
    }

    private void revokeUnregistered(Set<UUID> deviceIds, Instant now) {
        if (deviceIds.isEmpty()) return;
        for (PushDevice device : pushDeviceRepository.findAllById(deviceIds)) {
            String tokenRef = device.tokenRef();
            if (device.revoke(DeviceRevocationReason.DEVICE_NOT_REGISTERED, now)) {
                log.info("Thu hồi thiết bị vì Expo báo DeviceNotRegistered: deviceId={}, tokenRef={}",
                        device.getId(), tokenRef);
            }
        }
    }

    private static <K, V> Map<K, V> byId(List<V> values, Function<V, K> key) {
        return values.stream().collect(Collectors.toMap(key, Function.identity()));
    }
}
