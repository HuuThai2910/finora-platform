package com.finora.investment.messaging.event;

import java.time.Instant;

/**
 * Giấy xác nhận chuyển nhượng Note đã phát hành. Blockchain neo SHA-256 của chính object này; {@code documentHash} là
 * SHA-256 của tệp PDF đã đóng dấu nên đối chiếu được tệp với ledger. Không chứa họ tên hay CCCD.
 */
public record NoteTransferDocumentIssuedEventData(
        String documentNumber,
        String tradeReference,
        Long loanApplicationId,
        Long listingId,
        String documentHash,
        String hashAlgorithm,
        Instant issuedAt
) {
    public static final String EVENT_TYPE = "NoteTransferDocumentIssued";
    public static final int EVENT_VERSION = 1;
    public static final String HASH_ALGORITHM = "SHA-256";
}
