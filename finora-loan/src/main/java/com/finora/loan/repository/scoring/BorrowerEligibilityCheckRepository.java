package com.finora.loan.repository.scoring;

import com.finora.loan.domain.scoring.entity.BorrowerEligibilityCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BorrowerEligibilityCheckRepository extends JpaRepository<BorrowerEligibilityCheck, Long> {

    Optional<BorrowerEligibilityCheck> findByRequestId(String requestId);

    Optional<BorrowerEligibilityCheck> findFirstByApplicationIdOrderByCreatedAtDescIdDesc(Long applicationId);
}
