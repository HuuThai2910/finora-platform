package com.finora.loan.messaging.event;

import java.time.Instant;

/** {@code LoanFundingExpired.v1} từ Investment: đợt gọi vốn hết hạn mà chưa đủ vốn. */
public record LoanFundingExpiredEventData(
        Long loanApplicationId,
        String applicationNumber,
        Long listingId,
        Integer fundingRound,
        String targetAmount,
        String committedAmount,
        String currency,
        Instant fundingOpenedAt,
        Instant fundingClosedAt,
        String reason
) {
}
