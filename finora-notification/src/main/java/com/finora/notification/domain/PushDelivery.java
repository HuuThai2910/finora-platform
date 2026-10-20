package com.finora.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Một lần gửi push của một thông báo tới một thiết bị (D62). Gửi thất bại chỉ đổi dòng này, không bao giờ đụng tới
 * thông báo trong app hay state nghiệp vụ của service nguồn.
 * <p>
 * Máy trạng thái:
 * <pre>
 * PENDING ──claim──► SENDING ──Expo nhận──► SENT ──receipt ok──► CONFIRMED
 *    ▲                  │  └─lỗi tạm thời──► PENDING (lùi dần) … hết lượt ──► DEAD
 *    │                  ├─DeviceNotRegistered / lỗi vĩnh viễn ──► FAILED
 *    │                  └─thiết bị đã thu hồi lúc claim ──► SKIPPED
 *    └──── admin requeue (DEAD/FAILED) ───────────────────────────────┘
 * </pre>
 * {@code attemptCount} là claim token: chỉ worker đang giữ đúng lượt claim mới được ghi kết quả, worker cũ trả kết quả
 * muộn sau khi lease hết bị bỏ qua.
 */
@Entity
@Table(name = "notification_push_deliveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushDelivery {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "notification_id", nullable = false, updatable = false) private UUID notificationId;
    @Column(name = "device_id", nullable = false, updatable = false) private UUID deviceId;
    @Column(name = "recipient_id", nullable = false, length = 100, updatable = false) private String recipientId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false) private NotificationCategory category;
    @Column(nullable = false, length = 100, updatable = false) private String title;
    @Column(nullable = false, length = 200, updatable = false) private String body;
    /** Provider của lần gửi gần nhất ({@code LOG_MOCK} là giả lập, không tới thiết bị). */
    @Column(length = 20) private String provider;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private PushDeliveryStatus status;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "ticket_id", length = 100) private String ticketId;
    @Column(name = "receipt_check_after") private Instant receiptCheckAfter;
    @Column(name = "receipt_check_count", nullable = false) private int receiptCheckCount;
    @Column(name = "last_error_code", length = 60) private String lastErrorCode;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static PushDelivery pending(InAppNotification notification, PushDevice device, String title, String body,
            Instant firstAttemptAt, Instant now) {
        PushDelivery value = new PushDelivery();
        value.notificationId = notification.getId();
        value.deviceId = device.getId();
        value.recipientId = notification.getRecipientId();
        value.category = notification.getCategory();
        value.title = title;
        value.body = body;
        value.status = PushDeliveryStatus.PENDING;
        value.nextAttemptAt = firstAttemptAt;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    /** Nhận lượt gửi: {@code nextAttemptAt} thành lúc hết lease để worker khác nhận lại nếu worker này chết. */
    public void claim(String provider, Instant leaseUntil, Instant now) {
        status = PushDeliveryStatus.SENDING;
        attemptCount++;
        this.provider = provider;
        nextAttemptAt = leaseUntil;
        updatedAt = now;
    }

    public boolean isClaimedBy(int attempt) {
        return status == PushDeliveryStatus.SENDING && attemptCount == attempt;
    }

    public void skip(String reason, Instant now) {
        status = PushDeliveryStatus.SKIPPED;
        lastErrorCode = reason;
        nextAttemptAt = null;
        completedAt = now;
        updatedAt = now;
    }

    public void accepted(String ticketId, Instant receiptCheckAfter, Instant now) {
        status = PushDeliveryStatus.SENT;
        this.ticketId = ticketId;
        this.receiptCheckAfter = receiptCheckAfter;
        lastErrorCode = null;
        nextAttemptAt = null;
        sentAt = now;
        updatedAt = now;
    }

    public void failPermanently(String errorCode, Instant now) {
        status = PushDeliveryStatus.FAILED;
        lastErrorCode = errorCode;
        nextAttemptAt = null;
        receiptCheckAfter = null;
        completedAt = now;
        updatedAt = now;
    }

    /** Lỗi tạm thời: hẹn lại hoặc chuyển DEAD (dead-letter) khi đã hết lượt. */
    public void failRetryable(String errorCode, Instant retryAt, int maxAttempts, Instant now) {
        lastErrorCode = errorCode;
        receiptCheckAfter = null;
        updatedAt = now;
        if (attemptCount >= maxAttempts) {
            status = PushDeliveryStatus.DEAD;
            nextAttemptAt = null;
            completedAt = now;
        } else {
            status = PushDeliveryStatus.PENDING;
            nextAttemptAt = retryAt;
        }
    }

    /** Lượt đọc receipt; dời mốc đọc kế tiếp ngay khi nhận để hai worker không cùng hỏi Expo một ticket. */
    public void claimReceiptCheck(Instant nextCheck, Instant now) {
        receiptCheckCount++;
        receiptCheckAfter = nextCheck;
        updatedAt = now;
    }

    public void confirm(Instant now) {
        status = PushDeliveryStatus.CONFIRMED;
        receiptCheckAfter = null;
        completedAt = now;
        updatedAt = now;
    }

    /** Expo chưa có receipt sau số lần đọc tối đa: giữ SENT (đã gửi, chưa xác nhận) và thôi hỏi. */
    public void stopReceiptChecks(Instant now) {
        receiptCheckAfter = null;
        updatedAt = now;
    }

    public boolean isRequeueable() {
        return status == PushDeliveryStatus.DEAD || status == PushDeliveryStatus.FAILED;
    }

    /** Admin gửi lại sau khi sửa nguyên nhân (cấu hình Expo, quota); bắt đầu lại bộ đếm lượt. */
    public void requeue(Instant now) {
        status = PushDeliveryStatus.PENDING;
        attemptCount = 0;
        receiptCheckCount = 0;
        ticketId = null;
        receiptCheckAfter = null;
        lastErrorCode = null;
        completedAt = null;
        nextAttemptAt = now;
        updatedAt = now;
    }
}
