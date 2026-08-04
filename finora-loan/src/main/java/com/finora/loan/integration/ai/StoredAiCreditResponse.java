package com.finora.loan.integration.ai;

import com.finora.loan.domain.AiRecommendation;

import java.math.BigDecimal;

/** Snapshot allowlist để suggested_rate không được lưu trong JSON bằng chứng của Loan. */
public record StoredAiCreditResponse(
        BigDecimal pdProbability,
        Integer riskScore,
        BigDecimal evaluationScore,
        String creditGrade,
        BigDecimal suggestedLimit,
        AiRecommendation recommendation,
        String rejectionReason,
        String modelVersion
) {
    public static StoredAiCreditResponse from(AiCreditScoreResponse response) {
        return new StoredAiCreditResponse(
                response.pdProbability(),
                response.riskScore(),
                response.evaluationScore(),
                response.creditGrade(),
                response.suggestedLimit(),
                response.decision(),
                response.rejectionReason(),
                response.modelVersion()
        );
    }
}
