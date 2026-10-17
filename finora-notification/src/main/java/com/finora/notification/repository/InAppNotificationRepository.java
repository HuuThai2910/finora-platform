package com.finora.notification.repository;

import com.finora.notification.domain.InAppNotification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InAppNotificationRepository extends JpaRepository<InAppNotification, UUID> {
    boolean existsBySourceEventIdAndRecipientIdAndType(UUID eventId, String recipientId, String type);

    /** Dùng prefix của unique {@code uq_notification_delivery (source_event_id, recipient_id, type)}. */
    List<InAppNotification> findBySourceEventId(UUID sourceEventId);

    /** Dùng index {@code idx_notification_recipient_time (recipient_id, occurred_at DESC, id)}. */
    List<InAppNotification> findByRecipientIdOrderByOccurredAtDescIdDesc(String recipientId, Pageable pageable);

    long countByRecipientIdAndReadAtIsNull(String recipientId);

    Optional<InAppNotification> findByIdAndRecipientId(UUID id, String recipientId);

    /** "Đọc hết" bằng một câu lệnh thay vì một request mỗi tin; dùng index {@code (recipient_id, read_at, ...)}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update InAppNotification n set n.readAt = :now, n.updatedAt = :now
            where n.recipientId = :recipientId and n.readAt is null
            """)
    int markAllRead(@Param("recipientId") String recipientId, @Param("now") Instant now);
}
