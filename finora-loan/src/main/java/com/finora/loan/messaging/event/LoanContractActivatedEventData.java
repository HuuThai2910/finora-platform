package com.finora.loan.messaging.event;

import java.time.Instant;

/**
 * Kích hoạt nghĩa là đủ chữ ký; không đồng nghĩa Payment đã giải ngân.
 *
 * @param borrowerId ID logic của người vay (claim {@code user_id}); field optional thêm sau (P2, W3F), consumer cũ bỏ qua.
 *                   Blockchain neo hash của cả {@code data} nên field là chuỗi, không thêm số thực
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
        String borrowerId
) {
}
