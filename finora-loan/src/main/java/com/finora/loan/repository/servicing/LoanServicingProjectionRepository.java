package com.finora.loan.repository.servicing;

import com.finora.loan.domain.servicing.entity.LoanServicingProjection;
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

public interface LoanServicingProjectionRepository extends JpaRepository<LoanServicingProjection, Long> {
    Optional<LoanServicingProjection> findByFinoraLoanId(Long finoraLoanId);
    List<LoanServicingProjection> findByFinoraLoanIdIn(Collection<Long> finoraLoanIds);
    Page<LoanServicingProjection> findByStaleTrueOrderByUpdatedAtAscIdAsc(Pageable pageable);

    /** Khoản vay (trong {@code loanIds}) có số liệu lưu đã cũ so với hệ thống lõi: nhãn trên danh sách hồ sơ. */
    @Query("select projection.finoraLoanId from LoanServicingProjection projection "
            + "where projection.stale = true and projection.finoraLoanId in :loanIds")
    List<Long> findStaleLoanIds(@Param("loanIds") Collection<Long> loanIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select projection from LoanServicingProjection projection where projection.finoraLoanId = :loanId")
    Optional<LoanServicingProjection> findByFinoraLoanIdForUpdate(@Param("loanId") Long loanId);
}
