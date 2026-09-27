package com.finora.loan.repository.disbursement;

import com.finora.loan.domain.disbursement.DisbursementSaga;
import com.finora.loan.domain.disbursement.DisbursementSagaStatus;
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

public interface DisbursementSagaRepository extends JpaRepository<DisbursementSaga, Long> {
    Optional<DisbursementSaga> findByLoanApplicationId(Long loanApplicationId);
    Optional<DisbursementSaga> findByContractNumber(String contractNumber);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select saga from DisbursementSaga saga where saga.sagaId = :sagaId")
    Optional<DisbursementSaga> findBySagaIdForUpdate(@Param("sagaId") UUID sagaId);
    @Query("""
            select saga.id from DisbursementSaga saga
            where saga.status in :statuses and saga.nextRetryAt <= :now
            order by saga.nextRetryAt asc, saga.id asc
            """)
    List<Long> findDueIds(@Param("statuses") List<DisbursementSagaStatus> statuses,
                          @Param("now") Instant now, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select saga from DisbursementSaga saga where saga.id = :id")
    Optional<DisbursementSaga> findByIdForUpdate(@Param("id") Long id);
}

