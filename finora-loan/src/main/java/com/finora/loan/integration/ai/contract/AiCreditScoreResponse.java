package com.finora.loan.integration.ai.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.domain.scoring.AiRecommendation;
import java.math.BigDecimal;
import java.util.List;

/** Response đầy đủ của endpoint explain v17; không còn suggested_rate/suggested_limit. */
public record AiCreditScoreResponse(
        @JsonProperty("pd_probability") BigDecimal pdProbability,
        @JsonProperty("risk_score") Integer riskScore,
        @JsonProperty("evaluation_score") BigDecimal evaluationScore,
        @JsonProperty("credit_grade") String creditGrade,
        AiRecommendation decision,
        @JsonProperty("dien_giai") JsonNode borrowerExplanation,
        @JsonProperty("giai_thich_mo_hinh") JsonNode modelExplanation,
        @JsonProperty("rule_trace") JsonNode ruleTrace,
        @JsonProperty("rejection_reasons") List<String> rejectionReasons,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("decision_policy_version") String decisionPolicyVersion
) {
}
