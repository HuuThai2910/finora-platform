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
        Instant activatedAt,
        /** Mã hiển thị {@code HĐ-...}; trường bổ sung v1, producer cũ không gửi. */
        String contractCode
) {

    public LoanContractActivatedEventData(Long loanApplicationId, String applicationNumber, String contractNumber,
            Long listingId, String documentHash, String pdfReceiptHash, Long allocationVersion, String allocationHash,
            Instant activatedAt) {
        this(loanApplicationId, applicationNumber, contractNumber, listingId, documentHash, pdfReceiptHash,
                allocationVersion, allocationHash, activatedAt, null);
    }
}
