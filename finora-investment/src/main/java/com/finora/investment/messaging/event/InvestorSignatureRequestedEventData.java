package com.finora.investment.messaging.event;

import java.time.Instant;

public record InvestorSignatureRequestedEventData(
        Long loanApplicationId,
        String applicationNumber,
        Long listingId,
        String contractNumber,
        String documentHash,
        String pdfDocumentHash,
        Long allocationVersion,
        String allocationHash,
        int lenderCount,
        Instant expiresAt
) {
}
