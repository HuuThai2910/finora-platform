package com.finora.loan.repository.servicing;

import com.finora.loan.domain.servicing.entity.LoanServicingScheduleVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LoanServicingScheduleVersionRepository extends JpaRepository<LoanServicingScheduleVersion, Long> {
    Optional<LoanServicingScheduleVersion> findFirstByFinoraLoanIdOrderByScheduleVersionDesc(Long finoraLoanId);

    @Query("select coalesce(max(value.scheduleVersion), 0) from LoanServicingScheduleVersion value "
            + "where value.finoraLoanId = :loanId")
    int maximumVersion(@Param("loanId") Long loanId);
}
