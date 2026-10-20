package com.finora.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Dấu đã xử lý một event Kafka, ghi cùng transaction với thông báo được tạo (idempotent consumer). Không phải bản sao
 * payload: chỉ cần {@code eventId} để Kafka giao lại thì bỏ qua và {@code outcome} để vận hành biết vì sao event không
 * sinh thông báo.
 */
@Entity
@Table(name = "notification_processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    /** Kết quả xử lý một event. */
    public enum Outcome {
        /** Đã tạo ít nhất một thông báo (hoặc thông báo đã tồn tại từ lần trước). */
        DELIVERED,
        /** Event hợp lệ nhưng không có ai cần nhận (ví dụ producer cũ không gửi borrowerId). */
        NO_RECIPIENT,
        /** Cố ý không thông báo, ví dụ DPD tăng thêm một ngày trong cùng nhóm nợ (D61). */
        FILTERED,
        /** Hết thời gian chờ người nhận: chỉ gửi được cho người đã biết (D60). */
        EXPIRED_PARTIAL
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "event_id", nullable = false, unique = true, updatable = false) private UUID eventId;
    @Column(name = "event_type", nullable = false, length = 100, updatable = false) private String eventType;
    @Column(name = "event_version", nullable = false, updatable = false) private int eventVersion;
    @Column(name = "source_topic", nullable = false, length = 200, updatable = false) private String sourceTopic;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false) private Outcome outcome;
    @Column(name = "notification_count", nullable = false, updatable = false) private int notificationCount;
    @Column(name = "processed_at", nullable = false, updatable = false) private Instant processedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static ProcessedEvent create(UUID eventId, String eventType, int eventVersion, String sourceTopic,
            Outcome outcome, int notificationCount, Instant now) {
        ProcessedEvent value = new ProcessedEvent();
        value.eventId = eventId;
        value.eventType = eventType;
        value.eventVersion = eventVersion;
        value.sourceTopic = sourceTopic;
        value.outcome = outcome;
        value.notificationCount = notificationCount;
        value.processedAt = now;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }
}
