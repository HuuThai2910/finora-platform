package com.finora.loan.repository.scoring;

import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BorrowerEligibilityCheckRepository extends JpaRepository<BorrowerEligibilityCheck, Long> {

    Optional<BorrowerEligibilityCheck> findByRequestId(String requestId);

    Optional<BorrowerEligibilityCheck> findFirstByApplicationIdOrderByCreatedAtDescIdDesc(Long applicationId);
}
