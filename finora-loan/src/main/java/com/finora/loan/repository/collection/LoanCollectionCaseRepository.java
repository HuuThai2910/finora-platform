package com.finora.loan.repository.collection;

import com.finora.loan.domain.collection.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface LoanCollectionCaseRepository extends JpaRepository<LoanCollectionCase, Long> {
    Optional<LoanCollectionCase> findByCaseId(UUID caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from LoanCollectionCase value where value.caseId = :caseId")
    Optional<LoanCollectionCase> findByCaseIdForUpdate(@Param("caseId") UUID caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from LoanCollectionCase value where value.finoraLoanId = :loanId and value.status = :status")
    Optional<LoanCollectionCase> findByLoanIdAndStatusForUpdate(
            @Param("loanId") Long loanId, @Param("status") CollectionCaseStatus status);

    Page<LoanCollectionCase> findAllByOrderByDaysPastDueDescUpdatedAtAsc(Pageable pageable);
    Page<LoanCollectionCase> findByStatusOrderByDaysPastDueDescUpdatedAtAsc(
            CollectionCaseStatus status, Pageable pageable);
    Page<LoanCollectionCase> findByStageOrderByDaysPastDueDescUpdatedAtAsc(
            CollectionStage stage, Pageable pageable);
    Page<LoanCollectionCase> findByStatusAndStageOrderByDaysPastDueDescUpdatedAtAsc(
            CollectionCaseStatus status, CollectionStage stage, Pageable pageable);
}

