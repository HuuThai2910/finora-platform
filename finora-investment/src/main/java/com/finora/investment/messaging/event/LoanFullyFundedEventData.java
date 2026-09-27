package com.finora.investment.messaging.event;

import java.time.Instant;
import java.util.List;

public record LoanFullyFundedEventData(
        Long loanApplicationId,
        String applicationNumber,
        Long listingId,
        Integer fundingRound,
        String fundedAmount,
        String currency,
        Long allocationVersion,
        String allocationHash,
        Instant fundingCompletedAt,
        List<FundingAllocationEventData> allocations
) {
}
