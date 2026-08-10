package com.finora.loan.service.core;

import com.finora.loan.integration.fineract.contract.FineractProductConfiguration;

public record ProductSyncPreparation(
        String commandId,
        FineractProductConfiguration configuration,
        boolean executionRequired
) {
}
