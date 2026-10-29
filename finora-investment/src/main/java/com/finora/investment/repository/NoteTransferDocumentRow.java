package com.finora.investment.repository;

import com.finora.investment.domain.transferdocument.NoteTransferDocumentStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Một dòng giấy xác nhận chuyển nhượng kèm hai bên và số tiền của lần khớp, không có bytes PDF. */
public record NoteTransferDocumentRow(
        String documentNumber,
        String tradeReference,
        Long loanApplicationId,
        Long listingId,
        String sellerId,
        String buyerId,
        Integer quantity,
        BigDecimal amount,
        NoteTransferDocumentStatus status,
        String documentHash,
        Integer attempts,
        String lastError,
        Instant executedAt,
        Instant issuedAt
) {
}
