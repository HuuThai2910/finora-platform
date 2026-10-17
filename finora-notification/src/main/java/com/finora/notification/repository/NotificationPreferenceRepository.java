package com.finora.notification.repository;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationPreference;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    List<NotificationPreference> findByUserId(String userId);

    /** Intake fan-out: một câu cho mọi người nhận của một event, không truy vấn theo từng người (N+1). */
    List<NotificationPreference> findByUserIdInAndCategory(Collection<String> userIds, NotificationCategory category);

    /** Upsert nguyên tử: hai lần lưu cài đặt đồng thời không vấp unique {@code (user_id, category)}. */
    @Modifying
    @Query(value = """
            INSERT INTO notification_preferences (user_id, category, push_enabled, created_at, updated_at)
            VALUES (:userId, :category, :pushEnabled, :now, :now)
            ON CONFLICT (user_id, category)
            DO UPDATE SET push_enabled = EXCLUDED.push_enabled, updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int upsert(@Param("userId") String userId, @Param("category") String category,
            @Param("pushEnabled") boolean pushEnabled, @Param("now") Instant now);
}
