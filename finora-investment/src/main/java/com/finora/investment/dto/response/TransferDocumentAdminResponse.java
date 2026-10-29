package com.finora.investment.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Giấy xác nhận chuyển nhượng Note nhìn từ màn quản trị.
 *
 * @param documentHash  SHA-256 của PDF đã đóng dấu; null khi chưa phát hành
 * @param proofArtifact object {@code data} của event NoteTransferDocumentIssued, gửi kèm khi xác minh với
 *                      Blockchain; chỉ có ở API chi tiết
 */
public record TransferDocumentAdminResponse(
        String documentNumber,
        String tradeReference,
        Long loanApplicationId,
        Long listingId,
        String sellerId,
        String buyerId,
        int quantity,
        String amount,
        String status,
        String documentHash,
        int attempts,
        String lastError,
        Instant executedAt,
        Instant issuedAt,
        JsonNode proofArtifact
) {
}
