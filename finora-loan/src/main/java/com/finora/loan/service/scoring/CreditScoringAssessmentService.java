package com.finora.loan.service.scoring;

import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.scoring.request.ScoringRetryRequest;
import com.finora.loan.dto.scoring.response.CreditAssessmentDetailResponse;
import com.finora.loan.dto.scoring.response.CreditAssessmentSummaryResponse;
import com.finora.loan.dto.scoring.response.ScoringRetryAcceptedResponse;

public interface CreditScoringAssessmentService {

    PageResponse<CreditAssessmentSummaryResponse> list(String applicationNumber, int page, int size);

    CreditAssessmentDetailResponse detail(String applicationNumber, Long assessmentId);

    ScoringRetryAcceptedResponse retry(
            String applicationNumber,
            String idempotencyKey,
            ScoringRetryRequest request
    );
}
