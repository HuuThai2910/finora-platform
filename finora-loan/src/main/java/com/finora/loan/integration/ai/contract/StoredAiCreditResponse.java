package com.finora.loan.integration.ai.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.domain.scoring.AiRecommendation;
import java.math.BigDecimal;
import java.util.List;

/** Snapshot allowlist của toàn bộ bằng chứng v17 cần cho người vay, thẩm định viên và kiểm toán. */
public record StoredAiCreditResponse(
        BigDecimal pdProbability,
        Integer riskScore,
        BigDecimal evaluationScore,
        String creditGrade,
        AiRecommendation recommendation,
        JsonNode borrowerExplanation,
        JsonNode modelExplanation,
        JsonNode ruleTrace,
        List<String> rejectionReasons,
        String modelVersion,
        String decisionPolicyVersion
) {
    public static StoredAiCreditResponse from(AiCreditScoreResponse response) {
        return new StoredAiCreditResponse(
                response.pdProbability(),
                response.riskScore(),
                response.evaluationScore(),
                response.creditGrade(),
                response.decision(),
                response.borrowerExplanation(),
                response.modelExplanation(),
                response.ruleTrace(),
                response.rejectionReasons() == null ? List.of() : List.copyOf(response.rejectionReasons()),
                response.modelVersion(),
                response.decisionPolicyVersion()
        );
    }
}
