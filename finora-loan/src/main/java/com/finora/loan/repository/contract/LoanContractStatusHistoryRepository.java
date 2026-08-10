package com.finora.loan.repository.contract;

import com.finora.loan.domain.contract.LoanContractStatusHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanContractStatusHistoryRepository extends JpaRepository<LoanContractStatusHistory, Long> {

    Page<LoanContractStatusHistory> findByContractId(Long contractId, Pageable pageable);
}
