package com.finora.loan.integration.ai;

/** Boundary thay thế được bằng contract fixture; service nghiệp vụ không phụ thuộc HTTP client cụ thể. */
public interface AiCreditScoringGateway {

    AiCreditScoreResponse score(AiCreditScoreRequest request, String requestId);
}
