package com.finora.loan.integration.ai.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.finora.loan.domain.scoring.AiRecommendation;
import java.math.BigDecimal;

/** DTO biên có suggested_rate để đọc response hiện hành; mapper Loan cố ý không chuyển field này vào domain. */
public record AiCreditScoreResponse(
        @JsonProperty("pd_probability") BigDecimal pdProbability,
        @JsonProperty("risk_score") Integer riskScore,
        @JsonProperty("evaluation_score") BigDecimal evaluationScore,
        @JsonProperty("credit_grade") String creditGrade,
        @JsonProperty("suggested_limit") BigDecimal suggestedLimit,
        @JsonProperty("suggested_rate") BigDecimal ignoredSuggestedRate,
        AiRecommendation decision,
        @JsonProperty("rejection_reason") String rejectionReason,
        @JsonProperty("model_version") String modelVersion
) {
}
