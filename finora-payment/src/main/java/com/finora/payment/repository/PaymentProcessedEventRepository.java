package com.finora.payment.repository;
import com.finora.payment.domain.messaging.PaymentProcessedEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PaymentProcessedEventRepository extends JpaRepository<PaymentProcessedEvent,Long>{ boolean existsByEventId(UUID eventId); }

