package com.finora.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * Một thông báo trong hộp thư của người dùng, cũng là bản đầy đủ chi tiết (số tiền, mã khoản vay) mà push chỉ dẫn tới.
 * <p>
 * Idempotency theo {@code sourceEventId + recipientId + type}: {@code type} là mã mẫu nên một event chỉ tạo tối đa một
 * thông báo mỗi loại cho mỗi người nhận dù Kafka giao lại hay worker chạy lại. {@code templateVersion} ghi bản mẫu đã
 * render để đối chiếu khi đổi câu chữ, không nằm trong khóa vì event đã xử lý không được render lại.
 */
@Entity
@Table(name = "in_app_notifications", uniqueConstraints = @UniqueConstraint(
        name = "uq_notification_delivery", columnNames = {"source_event_id", "recipient_id", "type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InAppNotification implements Persistable<UUID> {
    @Id private UUID id;
    @Column(name = "source_event_id", nullable = false, updatable = false) private UUID sourceEventId;
    @Column(name = "recipient_id", nullable = false, length = 100, updatable = false) private String recipientId;
    @Column(nullable = false, length = 50, updatable = false) private String type;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false) private NotificationCategory category;
    @Column(name = "template_version", nullable = false, updatable = false) private int templateVersion;
    @Column(nullable = false, length = 160, updatable = false) private String title;
    @Column(nullable = false, length = 1000, updatable = false) private String message;
    @Column(name = "business_reference", length = 100, updatable = false) private String businessReference;
    @Column(name = "external_push_required", nullable = false, updatable = false) private boolean externalPushRequired;
    @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;
    @Column(name = "read_at") private Instant readAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    /** ID do ứng dụng sinh nên Spring Data không tự biết bản ghi mới; cờ này tránh SELECT thừa trước mỗi INSERT. */
    @Transient private boolean isNew;

    public static InAppNotification create(UUID sourceEventId, String recipientId, String type,
            NotificationCategory category, int templateVersion, String title, String message,
            String businessReference, boolean externalPushRequired, Instant occurredAt, Instant now) {
        InAppNotification value = new InAppNotification();
        value.id = UUID.randomUUID();
        value.sourceEventId = sourceEventId;
        value.recipientId = recipientId;
        value.type = type;
        value.category = category;
        value.templateVersion = templateVersion;
        value.title = title;
        value.message = message;
        value.businessReference = businessReference;
        value.externalPushRequired = externalPushRequired;
        value.occurredAt = occurredAt;
        value.createdAt = now;
        value.updatedAt = now;
        value.isNew = true;
        return value;
    }

    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
            updatedAt = now;
        }
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        isNew = false;
    }
}
