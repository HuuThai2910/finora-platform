package com.finora.investment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tiến độ gọi vốn của một khoản vay, dùng cho màn hình theo dõi và cho finora-loan đối soát.
 */
public record FundingProgressResponse(
        Long listingId,
        Long loanId,
        String targetAmount,
        String committedAmount,
        String remainingAmount,
        BigDecimal fundedPercent,
        int investorCount,
        int committedNoteCount,
        String status,
        Instant fundingClosesAt,
        Instant fullyFundedAt
) {
}
