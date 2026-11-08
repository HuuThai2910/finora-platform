package com.finora.loan.repository.servicing;

import com.finora.loan.domain.servicing.entity.PendingScheduledRepayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PendingScheduledRepaymentRepository extends JpaRepository<PendingScheduledRepayment, Long> {
    Optional<PendingScheduledRepayment> findByLoanApplicationId(Long loanApplicationId);
    void deleteByLoanApplicationIdAndRepaymentId(Long loanApplicationId, UUID repaymentId);
}
