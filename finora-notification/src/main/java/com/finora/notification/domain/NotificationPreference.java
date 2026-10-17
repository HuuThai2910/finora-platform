package com.finora.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Lựa chọn push của một người dùng cho một nhóm thông báo (D63). Chưa có dòng nghĩa là bật: người dùng đã đồng ý ở bước
 * cấp quyền thông báo của hệ điều hành, màn cài đặt chỉ để tắt bớt nhóm không muốn. Thông báo trong app không tắt được
 * vì đó là bản ghi đầy đủ người dùng cần đọc lại.
 */
@Entity
@Table(name = "notification_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreference {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false, length = 100, updatable = false) private String userId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false) private NotificationCategory category;
    @Column(name = "push_enabled", nullable = false) private boolean pushEnabled;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
