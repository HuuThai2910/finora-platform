package com.finora.loan.repository.servicing;

import com.finora.loan.domain.servicing.entity.*;
import com.finora.loan.domain.servicing.enums.*;
import com.finora.loan.domain.servicing.model.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LoanReconciliationIncidentRepository
        extends JpaRepository<LoanReconciliationIncident, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from LoanReconciliationIncident value where value.finoraLoanId = :loanId "
            + "and value.type = :type and value.status = com.finora.loan.domain.servicing.enums.ReconciliationIncidentStatus.OPEN")
    Optional<LoanReconciliationIncident> findOpenForUpdate(
            @Param("loanId") Long loanId, @Param("type") ReconciliationIncidentType type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from LoanReconciliationIncident value where value.finoraLoanId = :loanId "
            + "and value.status = com.finora.loan.domain.servicing.enums.ReconciliationIncidentStatus.OPEN")
    List<LoanReconciliationIncident> findOpenByLoanIdForUpdate(@Param("loanId") Long loanId);

    Page<LoanReconciliationIncident> findByStatusOrderByUpdatedAtAscIdAsc(
            ReconciliationIncidentStatus status, Pageable pageable);

    /** Sai lệch của một khoản vay theo trạng thái (phần "Sau giải ngân" của trang hồ sơ). */
    Page<LoanReconciliationIncident> findByLoanNumberAndStatusOrderByUpdatedAtAscIdAsc(
            String loanNumber, ReconciliationIncidentStatus status, Pageable pageable);

    /** Khoản vay (trong {@code loanIds}) có sai lệch ở trạng thái {@code status}: nhãn trên danh sách hồ sơ. */
    @Query("select distinct value.finoraLoanId from LoanReconciliationIncident value "
            + "where value.status = :status and value.finoraLoanId in :loanIds")
    List<Long> findLoanIdsWithStatus(@Param("status") ReconciliationIncidentStatus status,
            @Param("loanIds") Collection<Long> loanIds);

    Page<LoanReconciliationIncident> findByStatusAndTypeOrderByUpdatedAtAscIdAsc(
            ReconciliationIncidentStatus status, ReconciliationIncidentType type, Pageable pageable);
}
