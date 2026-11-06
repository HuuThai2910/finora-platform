package com.finora.loan.service.scoring;

import com.finora.loan.domain.scoring.entity.CreditScoringAssessment;
import com.finora.loan.dto.scoring.request.ScoringRetryRequest;

/**
 * Thao tác quản trị trên assessment chấm điểm: mở lại assessment {@code FAILED} để chấm lại.
 */
public interface CreditScoringAdminStateService {

    /** Idempotency record, reopen assessment và Application transition cùng một transaction. */
    CreditScoringAssessment retry(
            String applicationNumber,
            String normalizedKey,
            ScoringRetryRequest request,
            String actorId
    );
}
