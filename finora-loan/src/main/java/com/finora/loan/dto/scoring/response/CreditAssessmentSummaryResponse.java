package com.finora.loan.dto.scoring.response;

import com.finora.loan.domain.scoring.AiRecommendation;
import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record CreditAssessmentSummaryResponse(
        Long id,
        Long applicationId,
        String requestId,
        CreditAssessmentStatus status,
        Integer attemptCount,
        String requestedModelVersion,
        String actualModelVersion,
        BigDecimal pdProbability,
        Integer riskScore,
        BigDecimal evaluationScore,
        String creditGrade,
        BigDecimal suggestedLimit,
        AiRecommendation aiRecommendation,
        String failureCode,
        Instant nextRetryAt,
        Instant scoredAt,
        Instant createdAt
) {
}
