package com.finora.loan.dto.decision.response;

import com.finora.loan.domain.scoring.AiRecommendation;
import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record AdminAssessmentEvidenceResponse(
        Long assessmentId,
        CreditAssessmentStatus status,
        String actualModelVersion,
        BigDecimal pdProbability,
        Integer riskScore,
        BigDecimal evaluationScore,
        String creditGrade,
        BigDecimal suggestedLimit,
        AiRecommendation aiRecommendation,
        String rejectionReason,
        String decisionPolicyVersion,
        Instant scoredAt
) {
}
