package com.finora.loan.messaging.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Không chứa investorId/PII; Investment tự tra commitment theo listing của chính service.
 *
 * @param applicationCode mã hiển thị {@code HS-...} của hồ sơ; field optional thêm sau, consumer cũ bỏ qua
 * @param contractCode    mã hiển thị {@code HĐ-...} của hợp đồng; khóa đối chiếu vẫn là {@code contractNumber}
 */
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
        @JsonInclude(JsonInclude.Include.NON_NULL) String applicationCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) String contractCode
) {
}
