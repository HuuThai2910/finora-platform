package com.finora.investment.dto.response;

import java.time.Instant;

/** Một Note đang thuộc một nhà đầu tư, cho trang chi tiết khách hàng ở web quản trị. */
public record AdminInvestorNoteResponse(
        String noteNumber,
        Long listingId,
        Long loanId,
        String principalAmount,
        String outstandingPrincipal,
        String interestReceived,
        String annualInterestRate,
        Integer termMonths,
        String status,
        Instant issuedAt
) {
}
