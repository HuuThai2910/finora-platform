package com.finora.loan.messaging.event;

import java.time.Instant;

/**
 * {@code BorrowerSignatureRequested.v1}: mọi bên cho vay đã ký, tới lượt người vay.
 *
 * @param borrowerId ID logic của người vay (claim {@code user_id}, không phải CCCD); field optional thêm sau (P1, W3F)
 *                   để Notification báo đúng người mà không cần bảng tra, consumer cũ bỏ qua
 */
public record BorrowerSignatureRequestedEventData(
        Long loanApplicationId,
        String applicationNumber,
        String contractNumber,
        String documentHash,
        String pdfDocumentHash,
        int lenderCount,
        Instant requestedAt,
        Instant expiresAt,
        String borrowerId
) {
}
