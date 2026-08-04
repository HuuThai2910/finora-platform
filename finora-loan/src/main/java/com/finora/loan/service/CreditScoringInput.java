package com.finora.loan.service;

import com.finora.loan.integration.ai.AiCreditScoreRequest;

public record CreditScoringInput(
        AiCreditScoreRequest request,
        CreditScoringInputSources sources,
        String inputJson,
        String sourcesJson,
        String inputHash
) {
}
