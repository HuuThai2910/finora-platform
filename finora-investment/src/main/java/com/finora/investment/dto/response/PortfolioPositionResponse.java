package com.finora.investment.dto.response;

import java.math.BigDecimal;

/**
 * Một vị thế trong danh mục — gộp toàn bộ Note của cùng một khoản vay.
 */
public record PortfolioPositionResponse(
        Long loanId,
        Long listingId,
        String purpose,
        String creditGrade,
        String annualInterestRate,
        Integer termMonths,
        int noteCount,
        String principalAmount,
        String outstandingPrincipal,
        String principalRepaid,
        String interestReceived,
        BigDecimal sharePercent,
        String status
) {
}
