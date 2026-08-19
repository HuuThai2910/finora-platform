package com.finora.investment.dto.response;

import com.finora.investment.domain.LoanListing;

import java.math.BigDecimal;
import java.time.Instant;

public record ListingResponse(
        Long id,
        Long loanApplicationId,
        Long borrowerId,
        BigDecimal amount,
        BigDecimal remainingAmount,
        Integer termMonths,
        String grade,
        BigDecimal interestRate,
        String status,
        Instant createdAt
) {
    public static ListingResponse from(LoanListing l) {
        return new ListingResponse(
                l.getId(), l.getLoanApplicationId(), l.getBorrowerId(),
                l.getAmount(), l.getRemainingAmount(), l.getTermMonths(),
                l.getGrade(), l.getInterestRate(), l.getStatus().name(),
                l.getCreatedAt());
    }
}
