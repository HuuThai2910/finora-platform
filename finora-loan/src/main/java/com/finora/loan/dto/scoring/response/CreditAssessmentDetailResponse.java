package com.finora.loan.dto.scoring.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.domain.scoring.AiRecommendation;
import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record CreditAssessmentDetailResponse(
        Long id,
        Long applicationId,
        Long eligibilityCheckId,
        String requestId,
        String logicalScoringKey,
        CreditAssessmentStatus status,
        Integer attemptCount,
        String requestedModelVersion,
        String actualModelVersion,
        JsonNode inputSnapshot,
        JsonNode inputSources,
        String inputHash,
        BigDecimal pdProbability,
        Integer riskScore,
        BigDecimal evaluationScore,
        String creditGrade,
        BigDecimal suggestedLimit,
        AiRecommendation aiRecommendation,
        String rejectionReason,
        JsonNode responseSnapshot,
        String responseHash,
        String decisionPolicyVersion,
        String failureCode,
        String failureDetail,
        Instant startedAt,
        Instant completedAt,
        Instant scoredAt,
        Instant nextRetryAt,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
}
