package com.finora.loan.repository.messaging;

import com.finora.loan.domain.messaging.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    boolean existsByEventId(UUID eventId);
}
