package com.finora.investment.dto.response;

import com.finora.investment.domain.LoanListing;

import java.math.BigDecimal;

public record FundingResponse(
        Long listingId,
        BigDecimal amount,
        BigDecimal fundedAmount,
        BigDecimal fundedPercentage,
        int investorCount,
        boolean fullyFunded
) {
    public static FundingResponse from(LoanListing l) {
        return new FundingResponse(
                l.getId(), l.getAmount(), l.getFundedAmount(),
                l.getFundedPercentage(), l.getInvestorCount(),
                l.isFullyFunded());
    }
}
