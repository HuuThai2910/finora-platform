package com.finora.investment.dto.response;

import com.finora.investment.domain.InvestmentNote;

import java.math.BigDecimal;
import java.time.Instant;

public record NoteResponse(
        Long id,
        Long listingId,
        Long investorId,
        BigDecimal principalAmount,
        String grade,
        BigDecimal interestRate,
        String status,
        Instant purchaseDate
) {
    public static NoteResponse from(InvestmentNote n) {
        return new NoteResponse(
                n.getId(),
                n.getListing().getId(),
                n.getInvestorId(),
                n.getPrincipalAmount(),
                n.getListing().getGrade(),
                n.getListing().getInterestRate(),
                n.getStatus().name(),
                n.getPurchaseDate());
    }
}
