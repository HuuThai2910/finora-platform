package com.finora.loan.repository;

import com.finora.loan.domain.BorrowerEligibilityCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BorrowerEligibilityCheckRepository extends JpaRepository<BorrowerEligibilityCheck, Long> {

    Optional<BorrowerEligibilityCheck> findByRequestId(String requestId);
}
