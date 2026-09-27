package com.finora.loan.messaging.event;

import java.time.Instant;

/** Kích hoạt nghĩa là đủ chữ ký; không đồng nghĩa Payment đã giải ngân. */
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
