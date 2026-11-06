package com.finora.investment.repository;

import com.finora.investment.domain.note.entity.InvestmentLoanServicingState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InvestmentLoanServicingStateRepository extends JpaRepository<InvestmentLoanServicingState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from InvestmentLoanServicingState value where value.loanApplicationId = :applicationId")
    Optional<InvestmentLoanServicingState> findByApplicationIdForUpdate(@Param("applicationId") Long applicationId);

    List<InvestmentLoanServicingState> findByLoanApplicationIdIn(List<Long> applicationIds);
}
