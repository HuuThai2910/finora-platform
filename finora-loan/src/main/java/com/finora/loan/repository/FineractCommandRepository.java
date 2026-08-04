package com.finora.loan.repository;

import com.finora.loan.domain.FineractCommand;
import com.finora.loan.domain.FineractCommandStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FineractCommandRepository extends JpaRepository<FineractCommand, Long> {

    Optional<FineractCommand> findByCommandId(String commandId);

    Optional<FineractCommand> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select command from FineractCommand command where command.commandId = :commandId")
    Optional<FineractCommand> findByCommandIdForUpdate(@Param("commandId") String commandId);

    @Query("""
            select command.commandId
            from FineractCommand command
            where command.status = :status and command.nextRetryAt <= :now
            order by command.nextRetryAt asc, command.id asc
            """)
    List<String> findDueCommandIds(
            @Param("status") FineractCommandStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );
}
