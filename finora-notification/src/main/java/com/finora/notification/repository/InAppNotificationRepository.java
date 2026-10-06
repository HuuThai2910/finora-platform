package com.finora.notification.repository;

import com.finora.notification.domain.InAppNotification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InAppNotificationRepository extends JpaRepository<InAppNotification, UUID> {
    boolean existsBySourceEventIdAndRecipientIdAndType(UUID eventId, String recipientId, String type);
    List<InAppNotification> findByRecipientIdOrderByOccurredAtDescIdDesc(String recipientId, Pageable pageable);
    long countByRecipientIdAndReadAtIsNull(String recipientId);
    java.util.Optional<InAppNotification> findByIdAndRecipientId(UUID id, String recipientId);
}
