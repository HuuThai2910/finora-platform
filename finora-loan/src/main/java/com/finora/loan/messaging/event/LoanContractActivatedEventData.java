package com.finora.loan.messaging.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Kích hoạt nghĩa là đủ chữ ký; không đồng nghĩa Payment đã giải ngân.
 *
 * @param borrowerId ID logic của người vay (claim {@code user_id}); field optional thêm sau (P2, W3F), consumer cũ bỏ qua.
 *                   Blockchain neo hash của cả {@code data} nên field là chuỗi, không thêm số thực
 * @param contractCode mã hiển thị {@code HĐ-...} của hợp đồng (field optional thêm sau); khóa đối chiếu vẫn là
 *                     {@code contractNumber}
 */
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
        String borrowerId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String contractCode
) {

    public LoanContractActivatedEventData(Long loanApplicationId, String applicationNumber, String contractNumber,
            Long listingId, String documentHash, String pdfReceiptHash, Long allocationVersion, String allocationHash,
            Instant activatedAt, String borrowerId) {
        this(loanApplicationId, applicationNumber, contractNumber, listingId, documentHash, pdfReceiptHash,
                allocationVersion, allocationHash, activatedAt, borrowerId, null);
    }
}
