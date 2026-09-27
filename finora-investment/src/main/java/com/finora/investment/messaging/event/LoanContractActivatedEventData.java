package com.finora.investment.messaging.event;

import java.time.Instant;

public record LoanContractActivatedEventData(
        Long loanApplicationId,
        String applicationNumber,
        String contractNumber,
        Long listingId,
        String documentHash,
        String pdfReceiptHash,
        Long allocationVersion,
        String allocationHash,
        Instant activatedAt
) {
}
