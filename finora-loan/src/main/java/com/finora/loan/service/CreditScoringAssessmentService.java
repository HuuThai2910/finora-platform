package com.finora.loan.service;

import com.finora.loan.dto.request.ScoringRetryRequest;
import com.finora.loan.dto.response.CreditAssessmentDetailResponse;
import com.finora.loan.dto.response.CreditAssessmentSummaryResponse;
import com.finora.loan.dto.response.PageResponse;

public interface CreditScoringAssessmentService {

    PageResponse<CreditAssessmentSummaryResponse> list(String applicationNumber, int page, int size);

    CreditAssessmentDetailResponse detail(String applicationNumber, Long assessmentId);

    CreditAssessmentDetailResponse retry(
            String applicationNumber,
            String idempotencyKey,
            ScoringRetryRequest request
    );
}
