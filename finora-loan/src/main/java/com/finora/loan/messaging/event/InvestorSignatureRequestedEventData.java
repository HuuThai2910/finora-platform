package com.finora.loan.messaging.event;

import java.time.Instant;

/** Không chứa investorId/PII; Investment tự tra commitment theo listing của chính service. */
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
