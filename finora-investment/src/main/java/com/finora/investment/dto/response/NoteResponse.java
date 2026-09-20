package com.finora.investment.dto.response;

import java.time.Instant;

/**
 * Một chứng chỉ đầu tư đã phát hành.
 */
public record NoteResponse(
        String noteNumber,
        Long loanId,
        String principalAmount,
        String outstandingPrincipal,
        String principalRepaid,
        String interestReceived,
        String annualInterestRate,
        Integer termMonths,
        String status,
        Instant issuedAt
) {
}
