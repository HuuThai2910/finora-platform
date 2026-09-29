package com.finora.payment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletTransactionResponse(
        UUID id,
        Instant occurredAt,
        String type,
        String description,
        BigDecimal amount,
        String direction,
        String referenceId
) {
}
