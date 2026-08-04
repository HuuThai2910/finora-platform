package com.finora.loan.service;

import com.finora.loan.domain.FineractCommandStatus;
import com.finora.loan.integration.fineract.FineractProductConfiguration;

public record ProductSyncExecution(
        String commandId,
        String idempotencyKey,
        FineractCommandStatus status,
        FineractProductConfiguration configuration
) {
}
