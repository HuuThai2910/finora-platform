package com.finora.loan.dto.application.response;

import com.finora.loan.domain.application.LoanFundingStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Projection read-only để client không suy đoán Contract từ trạng thái APPROVED. */
public record LoanFundingResponse(
        LoanFundingStatus status,
        Integer fundingRound,
        Integer listingVersion,
        Instant requestedAt,
        Long investmentListingId,
        BigDecimal fundedAmount,
        Instant fullyFundedAt
) {
}
