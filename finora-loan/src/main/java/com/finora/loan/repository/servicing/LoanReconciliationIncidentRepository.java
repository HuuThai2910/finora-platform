package com.finora.loan.repository.servicing;

import com.finora.loan.domain.servicing.*;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface LoanReconciliationIncidentRepository
        extends JpaRepository<LoanReconciliationIncident, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from LoanReconciliationIncident value where value.finoraLoanId = :loanId "
            + "and value.type = :type and value.status = com.finora.loan.domain.servicing.ReconciliationIncidentStatus.OPEN")
    Optional<LoanReconciliationIncident> findOpenForUpdate(
            @Param("loanId") Long loanId, @Param("type") ReconciliationIncidentType type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from LoanReconciliationIncident value where value.finoraLoanId = :loanId "
            + "and value.status = com.finora.loan.domain.servicing.ReconciliationIncidentStatus.OPEN")
    List<LoanReconciliationIncident> findOpenByLoanIdForUpdate(@Param("loanId") Long loanId);

    Page<LoanReconciliationIncident> findByStatusOrderByUpdatedAtAscIdAsc(
            ReconciliationIncidentStatus status, Pageable pageable);

    Page<LoanReconciliationIncident> findByStatusAndTypeOrderByUpdatedAtAscIdAsc(
            ReconciliationIncidentStatus status, ReconciliationIncidentType type, Pageable pageable);
}
