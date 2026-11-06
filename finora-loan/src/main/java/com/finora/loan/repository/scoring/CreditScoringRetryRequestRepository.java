package com.finora.loan.repository.scoring;

import com.finora.loan.domain.scoring.entity.CreditScoringRetryRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CreditScoringRetryRequestRepository extends JpaRepository<CreditScoringRetryRequest, Long> {

    Optional<CreditScoringRetryRequest> findByIdempotencyKey(String idempotencyKey);
}
