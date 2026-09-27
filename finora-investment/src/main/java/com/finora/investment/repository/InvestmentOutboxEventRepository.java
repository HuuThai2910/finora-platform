package com.finora.investment.repository;

import com.finora.investment.domain.outbox.InvestmentOutboxEvent;
import com.finora.investment.domain.outbox.InvestmentOutboxEventStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestmentOutboxEventRepository extends JpaRepository<InvestmentOutboxEvent, Long> {

    @Query("""
            select event.id from InvestmentOutboxEvent event
            where (event.status = :pending and event.availableAt <= :now)
               or (event.status = :processing and event.processingStartedAt <= :leaseExpiredBefore)
            order by event.availableAt asc, event.id asc
            """)
    List<Long> findDueIds(
            @Param("pending") InvestmentOutboxEventStatus pending,
            @Param("processing") InvestmentOutboxEventStatus processing,
            @Param("now") Instant now,
            @Param("leaseExpiredBefore") Instant leaseExpiredBefore,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from InvestmentOutboxEvent event where event.id = :id")
    Optional<InvestmentOutboxEvent> findByIdForUpdate(@Param("id") Long id);
}
