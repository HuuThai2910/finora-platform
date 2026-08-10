package com.finora.loan.service;

import com.finora.loan.integration.ai.AiCreditScoreRequest;

public record CreditScoringExecution(
        Long assessmentId,
        String requestId,
        AiCreditScoreRequest request,
        boolean executionRequired
) {
    public static CreditScoringExecution skip(Long assessmentId) {
        return new CreditScoringExecution(assessmentId, null, null, false);
    }
}
