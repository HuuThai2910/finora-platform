package com.finora.payment.repository;

import com.finora.payment.domain.disbursement.PaymentDisbursement;
import com.finora.payment.domain.disbursement.PaymentDisbursementStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentDisbursementRepository extends JpaRepository<PaymentDisbursement,Long>{
    Optional<PaymentDisbursement> findBySagaId(UUID sagaId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from PaymentDisbursement d where d.id=:id")
    Optional<PaymentDisbursement> findByIdForUpdate(@Param("id") Long id);
    @Query("select d.id from PaymentDisbursement d where d.status in :statuses and d.nextAttemptAt<=:now order by d.nextAttemptAt,d.id")
    List<Long> findDueIds(@Param("statuses") List<PaymentDisbursementStatus> statuses,@Param("now") Instant now, Pageable page);
}

