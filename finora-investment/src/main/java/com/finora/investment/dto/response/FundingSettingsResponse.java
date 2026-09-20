package com.finora.investment.dto.response;

import java.time.Instant;

/**
 * Tham số gọi vốn đang áp dụng.
 *
 * <p>Số tiền trả về dạng chuỗi như mọi DTO khác của service, để client không mất chính
 * xác khi parse JSON.</p>
 */
public record FundingSettingsResponse(
        String noteDenomination,
        String minInvestmentAmount,
        Integer fundingDays,
        Instant updatedAt,
        String updatedBy
) {
}
