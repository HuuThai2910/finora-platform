package com.finora.loan.service.scoring;

import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.scoring.request.ScoringRetryRequest;
import com.finora.loan.dto.scoring.response.CreditAssessmentDetailResponse;
import com.finora.loan.dto.scoring.response.CreditAssessmentSummaryResponse;
import com.finora.loan.dto.scoring.response.ScoringRetryAcceptedResponse;

/**
 * Tra cứu assessment chấm điểm của một hồ sơ và yêu cầu chấm lại thủ công cho màn quản trị.
 */
public interface CreditScoringAssessmentService {

    PageResponse<CreditAssessmentSummaryResponse> list(String applicationNumber, int page, int size);

    CreditAssessmentDetailResponse detail(String applicationNumber, Long assessmentId);

    ScoringRetryAcceptedResponse retry(
            String applicationNumber,
            String idempotencyKey,
            ScoringRetryRequest request
    );
}
