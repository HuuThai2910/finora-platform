package com.finora.loan.repository.disbursement;

import com.finora.loan.domain.disbursement.entity.DisbursementSaga;
import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisbursementSagaRepository extends JpaRepository<DisbursementSaga, Long> {
    Optional<DisbursementSaga> findByLoanApplicationId(Long loanApplicationId);

    /** Saga giải ngân của các hồ sơ trên một trang danh sách quản trị (mỗi hồ sơ tối đa một saga). */
    List<DisbursementSaga> findByLoanApplicationIdIn(Collection<Long> loanApplicationIds);
    Optional<DisbursementSaga> findByContractNumber(String contractNumber);
    List<DisbursementSaga> findByBorrowerIdAndStatusInOrderByCreatedAtDescIdDesc(
            String borrowerId, Collection<DisbursementSagaStatus> statuses);
    org.springframework.data.domain.Page<DisbursementSaga> findByStatusOrderByUpdatedAtAscIdAsc(
            DisbursementSagaStatus status, org.springframework.data.domain.Pageable pageable);
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

