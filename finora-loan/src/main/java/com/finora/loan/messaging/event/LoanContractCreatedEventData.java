package com.finora.loan.messaging.event;

import java.time.Instant;

/** Payload không chứa PII; consumer truy vấn API owner nếu cần thêm dữ liệu được phép. */
public record LoanContractCreatedEventData(
        String contractNumber,
        Long applicationId,
        String documentHash,
        String pdfDocumentHash,
        String termsVersion,
        Instant expiresAt
) {
}
