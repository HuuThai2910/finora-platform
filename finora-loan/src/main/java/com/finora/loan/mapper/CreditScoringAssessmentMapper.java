package com.finora.loan.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.CreditScoringAssessment;
import com.finora.loan.dto.response.CreditAssessmentDetailResponse;
import com.finora.loan.dto.response.CreditAssessmentSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreditScoringAssessmentMapper {

    private final ObjectMapper objectMapper;

    public CreditAssessmentSummaryResponse toSummary(CreditScoringAssessment assessment) {
        return new CreditAssessmentSummaryResponse(
                assessment.getId(), assessment.getApplicationId(), assessment.getRequestId(),
                assessment.getStatus(), assessment.getAttemptCount(), assessment.getRequestedModelVersion(),
                assessment.getActualModelVersion(), assessment.getPdProbability(), assessment.getRiskScore(),
                assessment.getEvaluationScore(), assessment.getCreditGrade(), assessment.getSuggestedLimit(),
                assessment.getAiRecommendation(), assessment.getFailureCode(), assessment.getNextRetryAt(),
                assessment.getScoredAt(), assessment.getCreatedAt()
        );
    }

    public CreditAssessmentDetailResponse toDetail(CreditScoringAssessment assessment) {
        return new CreditAssessmentDetailResponse(
                assessment.getId(), assessment.getApplicationId(), assessment.getEligibilityCheckId(),
                assessment.getRequestId(), assessment.getLogicalScoringKey(), assessment.getStatus(),
                assessment.getAttemptCount(), assessment.getRequestedModelVersion(), assessment.getActualModelVersion(),
                json(assessment.getInputSnapshotJson()), json(assessment.getInputSourceSnapshotJson()),
                assessment.getInputHash(), assessment.getPdProbability(), assessment.getRiskScore(),
                assessment.getEvaluationScore(), assessment.getCreditGrade(), assessment.getSuggestedLimit(),
                assessment.getAiRecommendation(), assessment.getRejectionReason(),
                json(assessment.getResponseSnapshotJson()), assessment.getResponseHash(),
                assessment.getDecisionPolicyVersion(), assessment.getFailureCode(), assessment.getFailureDetail(),
                assessment.getStartedAt(), assessment.getCompletedAt(), assessment.getScoredAt(),
                assessment.getNextRetryAt(), assessment.getVersion(), assessment.getCreatedAt(), assessment.getUpdatedAt()
        );
    }

    private JsonNode json(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Assessment snapshot trong DB không phải JSON hợp lệ", exception);
        }
    }
}
