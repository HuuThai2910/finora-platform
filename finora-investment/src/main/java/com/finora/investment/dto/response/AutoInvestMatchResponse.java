package com.finora.investment.dto.response;

import java.time.Instant;

/**
 * Một dòng lịch sử Auto-Invest, kèm thông tin khoản vay để app hiển thị không cần gọi thêm.
 */
public record AutoInvestMatchResponse(
        Instant at,
        Long listingId,
        String applicationNumber,
        String creditGrade,
        String annualInterestRate,
        String outcome,
        String reason,
        String amount,
        String orderReference
) {
}
