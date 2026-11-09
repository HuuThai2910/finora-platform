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
        Instant expiresAt,
        /** Mã hiển thị {@code HS-...}/{@code HĐ-...}; trường bổ sung v1, producer cũ không gửi. */
        String applicationCode,
        String contractCode
) {

    public InvestorSignatureRequestedEventData(Long loanApplicationId, String applicationNumber, Long listingId,
            String contractNumber, String documentHash, String pdfDocumentHash, Long allocationVersion,
            String allocationHash, int lenderCount, Instant expiresAt) {
        this(loanApplicationId, applicationNumber, listingId, contractNumber, documentHash, pdfDocumentHash,
                allocationVersion, allocationHash, lenderCount, expiresAt, null, null);
    }
}
