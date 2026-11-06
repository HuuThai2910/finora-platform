package com.finora.loan.repository.servicing;

import com.finora.loan.domain.servicing.entity.RepaymentEventQuarantine;
import com.finora.loan.domain.servicing.enums.RepaymentEventQuarantineStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RepaymentEventQuarantineRepository extends JpaRepository<RepaymentEventQuarantine, Long> {
    Optional<RepaymentEventQuarantine> findByEventId(UUID eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from RepaymentEventQuarantine value where value.eventId = :eventId")
    Optional<RepaymentEventQuarantine> findByEventIdForUpdate(@Param("eventId") UUID eventId);

    Page<RepaymentEventQuarantine> findByStatusOrderByCreatedAtAscIdAsc(
            RepaymentEventQuarantineStatus status, Pageable pageable);
}
