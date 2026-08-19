package com.finora.investment.dto.response;

import com.finora.investment.domain.MatchResult;

import java.math.BigDecimal;
import java.time.Instant;

public record MatchResponse(
        Long id,
        Long listingId,
        Long orderId,
        BigDecimal matchedAmount,
        Instant matchedAt
) {
    public static MatchResponse from(MatchResult m) {
        return new MatchResponse(
                m.getId(), m.getListing().getId(), m.getOrder().getId(),
                m.getMatchedAmount(), m.getMatchedAt());
    }
}
