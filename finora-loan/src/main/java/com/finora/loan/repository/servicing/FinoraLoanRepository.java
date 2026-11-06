package com.finora.loan.repository.servicing;

import com.finora.loan.domain.servicing.entity.FinoraLoan;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FinoraLoanRepository extends JpaRepository<FinoraLoan, Long> {
    Optional<FinoraLoan> findByLoanNumber(String loanNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select loan from FinoraLoan loan where loan.loanNumber = :loanNumber")
    Optional<FinoraLoan> findByLoanNumberForUpdate(@Param("loanNumber") String loanNumber);
    Optional<FinoraLoan> findByLoanApplicationId(Long loanApplicationId);
    List<FinoraLoan> findByLoanApplicationIdIn(Collection<Long> loanApplicationIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select loan from FinoraLoan loan where loan.loanApplicationId = :loanApplicationId")
    Optional<FinoraLoan> findByLoanApplicationIdForUpdate(
            @Param("loanApplicationId") Long loanApplicationId);

    Page<FinoraLoan> findByBorrowerIdOrderByDisbursedAtDesc(String borrowerId, Pageable pageable);

    /** Gốc còn lại theo projection Fineract của các khoản đang phục vụ của người vay. */
    @Query("""
            select coalesce(sum(projection.principalOutstanding), 0)
            from FinoraLoan loan, LoanServicingProjection projection
            where projection.finoraLoanId = loan.id and loan.borrowerId = :borrowerId and loan.status in :statuses
            """)
    java.math.BigDecimal sumOutstandingPrincipal(
            @Param("borrowerId") String borrowerId,
            @Param("statuses") List<com.finora.loan.domain.servicing.enums.FinoraLoanStatus> statuses);

    @Query("""
            select loan.id from FinoraLoan loan, LoanServicingProjection projection
            where projection.finoraLoanId = loan.id and loan.status in :statuses
            order by projection.updatedAt, loan.id
            """)
    List<Long> findIdsByStatusesOrderedByOldestProjection(
            @Param("statuses") List<com.finora.loan.domain.servicing.enums.FinoraLoanStatus> statuses,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select loan from FinoraLoan loan where loan.id = :id")
    Optional<FinoraLoan> findByIdForUpdate(@Param("id") Long id);
}
