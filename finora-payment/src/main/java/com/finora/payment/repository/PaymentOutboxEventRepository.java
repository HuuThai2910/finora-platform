package com.finora.payment.repository;
import com.finora.payment.domain.outbox.entity.PaymentOutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
public interface PaymentOutboxEventRepository extends JpaRepository<PaymentOutboxEvent,Long>{
 @Query("select e.id from PaymentOutboxEvent e where e.status='PENDING' and e.availableAt<=:now order by e.availableAt,e.id")
 List<Long> findDueIds(@Param("now") Instant now, Pageable page);
}

