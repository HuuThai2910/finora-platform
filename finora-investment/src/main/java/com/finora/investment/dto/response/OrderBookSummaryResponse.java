package com.finora.investment.dto.response;

import java.time.Instant;

/** Một dòng trong danh sách sổ lệnh: khoản vay gốc và giá tốt nhất hai phía. */
public record OrderBookSummaryResponse(
        Long listingId,
        Long loanId,
        String creditGrade,
        String annualInterestRate,
        Integer termMonths,
        String noteDenomination,
        boolean defaulted,
        String bestBidPercent,
        String bestAskPercent,
        String lastTradePercent,
        Instant lastTradeAt
) {
}
