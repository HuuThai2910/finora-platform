package com.finora.loan.mapper.scoring;

import com.finora.loan.integration.ai.contract.AiCreditScoreRequest;

/**
 * Kết quả ánh xạ tại biên AI. Service dùng artifact này để lưu đúng request, nguồn dữ liệu
 * và hash đã được tạo trong cùng một lần ánh xạ.
 */
public record CreditScoringMapping(
        AiCreditScoreRequest request,
        CreditScoringSourceSnapshot sources,
        String inputJson,
        String sourcesJson,
        String inputHash
) {
}
