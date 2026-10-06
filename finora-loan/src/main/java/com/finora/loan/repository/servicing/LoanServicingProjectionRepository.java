package com.finora.loan.repository.servicing;

import com.finora.loan.domain.servicing.LoanServicingProjection;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanServicingProjectionRepository extends JpaRepository<LoanServicingProjection, Long> {
    Optional<LoanServicingProjection> findByFinoraLoanId(Long finoraLoanId);
    List<LoanServicingProjection> findByFinoraLoanIdIn(Collection<Long> finoraLoanIds);
    Page<LoanServicingProjection> findByStaleTrueOrderByUpdatedAtAscIdAsc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select projection from LoanServicingProjection projection where projection.finoraLoanId = :loanId")
    Optional<LoanServicingProjection> findByFinoraLoanIdForUpdate(@Param("loanId") Long loanId);
}
