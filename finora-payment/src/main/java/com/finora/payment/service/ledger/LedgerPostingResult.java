package com.finora.payment.service.ledger;

import com.finora.payment.domain.ledger.LedgerTransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerPostingResult(
        UUID transactionId,
        LedgerTransactionStatus status,
        BigDecimal totalAmount,
        Instant postedAt,
        boolean replayed
) {
}
