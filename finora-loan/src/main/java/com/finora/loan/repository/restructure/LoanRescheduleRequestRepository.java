package com.finora.loan.repository.restructure;

import com.finora.loan.domain.restructure.LoanRescheduleRequest;
import com.finora.loan.domain.restructure.LoanRescheduleStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanRescheduleRequestRepository extends JpaRepository<LoanRescheduleRequest, Long> {
    Optional<LoanRescheduleRequest> findByRequestId(UUID requestId);
    Optional<LoanRescheduleRequest> findByBorrowerIdAndIdempotencyKey(String borrowerId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from LoanRescheduleRequest request where request.requestId = :requestId")
    Optional<LoanRescheduleRequest> findByRequestIdForUpdate(@Param("requestId") UUID requestId);

    Page<LoanRescheduleRequest> findByBorrowerIdAndLoanNumberOrderByCreatedAtDesc(
            String borrowerId, String loanNumber, Pageable pageable);
    Page<LoanRescheduleRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<LoanRescheduleRequest> findByStatusOrderByCreatedAtDesc(LoanRescheduleStatus status, Pageable pageable);

    @Query("""
            select request.id from LoanRescheduleRequest request
            where request.status in (:readyStatuses)
                  and (request.nextAttemptAt is null or request.nextAttemptAt <= :now)
               or request.status in (:processingStatuses)
                  and request.processingStartedAt <= :leaseExpiredBefore
            order by request.updatedAt, request.id
            """)
    List<Long> findDueIds(
            @Param("readyStatuses") List<LoanRescheduleStatus> readyStatuses,
            @Param("processingStatuses") List<LoanRescheduleStatus> processingStatuses,
            @Param("now") Instant now,
            @Param("leaseExpiredBefore") Instant leaseExpiredBefore,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from LoanRescheduleRequest request where request.id = :id")
    Optional<LoanRescheduleRequest> findByIdForUpdate(@Param("id") Long id);
}

