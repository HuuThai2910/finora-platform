package com.finora.notification.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Event hợp lệ nhưng chưa biết người nhận vì event mang thông tin người nhận nằm ở topic khác và chưa tới (D60).
 * <p>
 * Ví dụ: Notification vừa bật lại sau một giờ dừng, hai listener cùng đọc dồn; {@code InvestorSignatureRequested} có
 * thể được đọc trước {@code LoanFullyFunded} (nơi có danh sách nhà đầu tư). Thay vì chặn partition hay đẩy vào DLT,
 * event được giữ lại và worker thử lại tới {@code expiresAt}; quá hạn thì gửi cho người đã biết và chuyển
 * {@link DeferredEventStatus#EXPIRED} để vận hành xem.
 */
@Entity
@Table(name = "notification_deferred_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeferredEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "event_id", nullable = false, unique = true, updatable = false) private UUID eventId;
    @Column(name = "event_type", nullable = false, length = 100, updatable = false) private String eventType;
    @Column(name = "event_version", nullable = false, updatable = false) private int eventVersion;
    @Column(name = "source_topic", nullable = false, length = 200, updatable = false) private String sourceTopic;
    @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;
    /** Phần {@code data} của envelope; theo contract không có PII. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payloadJson;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private DeferredEventStatus status;
    @Column(name = "wait_reason", nullable = false, length = 100) private String waitReason;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static DeferredEvent waiting(UUID eventId, String eventType, int eventVersion, String sourceTopic,
            Instant occurredAt, String payloadJson, String waitReason, Instant now, Duration retryInterval,
            Duration maxWait) {
        DeferredEvent value = new DeferredEvent();
        value.eventId = eventId;
        value.eventType = eventType;
        value.eventVersion = eventVersion;
        value.sourceTopic = sourceTopic;
        value.occurredAt = occurredAt;
        value.payloadJson = payloadJson;
        value.status = DeferredEventStatus.WAITING;
        value.waitReason = limited(waitReason);
        value.attemptCount = 0;
        value.nextAttemptAt = now.plus(retryInterval);
        value.expiresAt = now.plus(maxWait);
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    /**
     * Hẹn lần thử sau theo cấp số nhân, không vượt {@code maxInterval} và không trễ hơn lúc hết hạn: lần cuối cùng chạy
     * đúng {@code expiresAt} để gửi phần người nhận đã biết.
     */
    public void scheduleRetry(String reason, Instant now, Duration retryInterval, Duration maxInterval) {
        attemptCount++;
        waitReason = limited(reason);
        long factor = 1L << Math.min(attemptCount, 10);
        Duration delay = retryInterval.multipliedBy(factor);
        if (delay.compareTo(maxInterval) > 0) delay = maxInterval;
        Instant next = now.plus(delay);
        nextAttemptAt = next.isAfter(expiresAt) ? expiresAt : next;
        updatedAt = now;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void expire(Instant now) {
        status = DeferredEventStatus.EXPIRED;
        attemptCount++;
        updatedAt = now;
    }

    private static String limited(String reason) {
        if (reason == null || reason.isBlank()) return "RECIPIENT_UNKNOWN";
        return reason.length() <= 100 ? reason : reason.substring(0, 100);
    }
}
