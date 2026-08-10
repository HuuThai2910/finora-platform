package com.finora.loan.service.core;

import com.finora.loan.domain.core.FineractCommandStatus;
import com.finora.loan.integration.fineract.contract.FineractProductConfiguration;

public record ProductSyncExecution(
        String commandId,
        String idempotencyKey,
        FineractCommandStatus status,
        FineractProductConfiguration configuration,
        boolean executionRequired
) {

    public static ProductSyncExecution skip(String commandId, String idempotencyKey, FineractCommandStatus status) {
        return new ProductSyncExecution(commandId, idempotencyKey, status, null, false);
    }
}
