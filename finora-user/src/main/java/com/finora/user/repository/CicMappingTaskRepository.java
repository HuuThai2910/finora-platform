package com.finora.user.repository;

import com.finora.user.domain.CicMappingTask;
import com.finora.user.domain.CicMappingTaskStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CicMappingTaskRepository extends JpaRepository<CicMappingTask, Long> {

    Optional<CicMappingTask> findByUserProfileId(Long userProfileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from CicMappingTask task where task.id = :id")
    Optional<CicMappingTask> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select task.id from CicMappingTask task
            where task.status in :statuses and task.nextAttemptAt <= :now
            order by task.nextAttemptAt, task.id
            """)
    List<Long> findDueIds(
            @Param("statuses") Collection<CicMappingTaskStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable);
}
