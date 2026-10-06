package com.finora.payment.repository.servicing;

import com.finora.payment.domain.servicing.PaymentLoanAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentLoanAccountRepository extends JpaRepository<PaymentLoanAccount, Long> {
    Optional<PaymentLoanAccount> findByLoanApplicationId(Long loanApplicationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from PaymentLoanAccount account where account.loanApplicationId = :applicationId")
    Optional<PaymentLoanAccount> findByLoanApplicationIdForUpdate(@Param("applicationId") Long applicationId);
}
