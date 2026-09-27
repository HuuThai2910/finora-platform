package com.finora.payment.repository;
import com.finora.payment.domain.outbox.PaymentOutboxEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface PaymentOutboxEventRepository extends JpaRepository<PaymentOutboxEvent,Long>{
 @Query("select e.id from PaymentOutboxEvent e where e.status='PENDING' and e.availableAt<=:now order by e.availableAt,e.id")
 List<Long> findDueIds(@Param("now") Instant now, Pageable page);
}

