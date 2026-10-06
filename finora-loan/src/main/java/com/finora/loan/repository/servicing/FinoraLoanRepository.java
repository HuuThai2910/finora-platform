package com.finora.loan.repository.servicing;

import com.finora.loan.domain.servicing.FinoraLoan;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinoraLoanRepository extends JpaRepository<FinoraLoan, Long> {
    Optional<FinoraLoan> findByLoanNumber(String loanNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select loan from FinoraLoan loan where loan.loanNumber = :loanNumber")
    Optional<FinoraLoan> findByLoanNumberForUpdate(@Param("loanNumber") String loanNumber);
    Optional<FinoraLoan> findByLoanApplicationId(Long loanApplicationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select loan from FinoraLoan loan where loan.loanApplicationId = :loanApplicationId")
    Optional<FinoraLoan> findByLoanApplicationIdForUpdate(
            @Param("loanApplicationId") Long loanApplicationId);

    Page<FinoraLoan> findByBorrowerIdOrderByDisbursedAtDesc(String borrowerId, Pageable pageable);

    @Query("""
            select loan.id from FinoraLoan loan, LoanServicingProjection projection
            where projection.finoraLoanId = loan.id and loan.status in :statuses
            order by projection.updatedAt, loan.id
            """)
    List<Long> findIdsByStatusesOrderedByOldestProjection(
            @Param("statuses") List<com.finora.loan.domain.servicing.FinoraLoanStatus> statuses,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select loan from FinoraLoan loan where loan.id = :id")
    Optional<FinoraLoan> findByIdForUpdate(@Param("id") Long id);
}
