package com.finora.loan.repository.scoring;

import com.finora.loan.domain.scoring.CreditScoringRetryRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditScoringRetryRequestRepository extends JpaRepository<CreditScoringRetryRequest, Long> {

    Optional<CreditScoringRetryRequest> findByIdempotencyKey(String idempotencyKey);
}
