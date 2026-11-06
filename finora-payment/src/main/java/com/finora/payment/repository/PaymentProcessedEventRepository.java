package com.finora.payment.repository;
import com.finora.payment.domain.messaging.entity.PaymentProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
public interface PaymentProcessedEventRepository extends JpaRepository<PaymentProcessedEvent,Long>{ boolean existsByEventId(UUID eventId); }

