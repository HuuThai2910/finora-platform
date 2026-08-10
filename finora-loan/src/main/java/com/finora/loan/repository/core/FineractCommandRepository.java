package com.finora.loan.repository.core;

import com.finora.loan.domain.core.FineractCommand;
import com.finora.loan.domain.core.FineractCommandStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FineractCommandRepository extends JpaRepository<FineractCommand, Long> {

    Optional<FineractCommand> findByCommandId(String commandId);

    Optional<FineractCommand> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select command from FineractCommand command where command.commandId = :commandId")
    Optional<FineractCommand> findByCommandIdForUpdate(@Param("commandId") String commandId);

    /**
     * Lấy cả command chưa chạy, retry đến hạn và PROCESSING đã mất lease.
     * Nhờ đó tiến trình dừng giữa prepare/HTTP không làm command mắc kẹt vĩnh viễn.
     */
    @Query("""
            select command.commandId
            from FineractCommand command
            where command.status = :pending
               or (command.status = :retryPending and command.nextRetryAt <= :now)
               or (command.status = :processing and command.updatedAt <= :staleBefore)
            order by command.updatedAt asc, command.id asc
            """)
    List<String> findDueCommandIds(
            @Param("pending") FineractCommandStatus pending,
            @Param("retryPending") FineractCommandStatus retryPending,
            @Param("processing") FineractCommandStatus processing,
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            Pageable pageable
    );
}
