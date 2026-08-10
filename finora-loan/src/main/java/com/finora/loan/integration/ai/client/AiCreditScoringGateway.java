package com.finora.loan.integration.ai.client;

import com.finora.loan.integration.ai.contract.AiCreditScoreRequest;
import com.finora.loan.integration.ai.contract.AiCreditScoreResponse;

/** Boundary thay thế được bằng contract fixture; service nghiệp vụ không phụ thuộc HTTP client cụ thể. */
public interface AiCreditScoringGateway {

    /** Chấm đúng input snapshot; requestId dùng correlation và retry, không được log payload tài chính. */
    AiCreditScoreResponse score(AiCreditScoreRequest request, String requestId);
}
