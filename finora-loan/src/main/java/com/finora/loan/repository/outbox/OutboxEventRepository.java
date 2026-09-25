package com.finora.loan.repository.outbox;

import com.finora.loan.domain.outbox.OutboxEvent;
import com.finora.loan.domain.outbox.OutboxEventStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("""
            select event.id from OutboxEvent event
            where (event.status = :pending and event.availableAt <= :now)
               or (event.status = :processing and event.processingStartedAt <= :leaseExpiredBefore)
            order by event.availableAt asc, event.id asc
            """)
    List<Long> findDueIds(
            @Param("pending") OutboxEventStatus pending,
            @Param("processing") OutboxEventStatus processing,
            @Param("now") Instant now,
            @Param("leaseExpiredBefore") Instant leaseExpiredBefore,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from OutboxEvent event where event.id = :id")
    Optional<OutboxEvent> findByIdForUpdate(@Param("id") Long id);
}
