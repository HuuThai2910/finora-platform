package com.finora.notification.repository;

import com.finora.notification.domain.DueSchedule;
import com.finora.notification.domain.DueScheduleStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DueScheduleRepository extends JpaRepository<DueSchedule, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DueSchedule s where s.loanApplicationId = :id")
    Optional<DueSchedule> findForUpdate(@Param("id") Long loanApplicationId);

    /**
     * Một lô khoản vay đến hạn đúng ngày {@code dueDate}, đọc tiếp sau {@code afterLoanId} (keyset, không OFFSET).
     * Dùng index {@code (status, next_due_date, loan_application_id)}.
     */
    @Query("""
            select s from DueSchedule s
            where s.status = :status and s.nextDueDate = :dueDate and s.loanApplicationId > :afterLoanId
            order by s.loanApplicationId
            """)
    List<DueSchedule> findDueBatch(@Param("status") DueScheduleStatus status, @Param("dueDate") LocalDate dueDate,
            @Param("afterLoanId") long afterLoanId, Pageable pageable);
}
