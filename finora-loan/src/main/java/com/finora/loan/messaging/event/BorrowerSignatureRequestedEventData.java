package com.finora.loan.messaging.event;

import java.time.Instant;

public record BorrowerSignatureRequestedEventData(
        Long loanApplicationId,
        String applicationNumber,
        String contractNumber,
        String documentHash,
        String pdfDocumentHash,
        int lenderCount,
        Instant requestedAt,
        Instant expiresAt
) {
}
