package com.finora.investment.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Cấu hình Auto-Invest hiện tại. Chưa lưu lần nào thì trả mặc định với {@code enabled=false}.
 */
public record AutoInvestConfigResponse(
        boolean enabled,
        List<String> grades,
        String minAnnualRate,
        Integer maxTermMonths,
        String amountPerLoan,
        Instant enabledAt
) {
}
