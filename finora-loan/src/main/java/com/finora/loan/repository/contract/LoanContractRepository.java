package com.finora.loan.repository.contract;

import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.LoanContractStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanContractRepository extends JpaRepository<LoanContract, Long> {

    Optional<LoanContract> findByContractNumber(String contractNumber);

    Optional<LoanContract> findByApplicationId(Long applicationId);

    Optional<LoanContract> findByConsentIdempotencyKey(String consentIdempotencyKey);

    Page<LoanContract> findByBorrowerId(String borrowerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select contract from LoanContract contract where contract.contractNumber = :contractNumber")
    Optional<LoanContract> findByContractNumberForUpdate(@Param("contractNumber") String contractNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select contract from LoanContract contract where contract.id = :id")
    Optional<LoanContract> findByIdForUpdate(@Param("id") Long id);

    /** Worker chỉ lấy ID đến hạn theo partial index rồi khóa từng Contract trong transaction riêng. */
    @Query("""
            select contract.id from LoanContract contract
            where contract.status = :status and contract.expiresAt <= :now
            order by contract.expiresAt asc, contract.id asc
            """)
    List<Long> findDueIds(
            @Param("status") LoanContractStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );
}
