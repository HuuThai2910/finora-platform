package com.finora.loan.service;

import com.finora.loan.integration.fineract.FineractProductConfiguration;

public record ProductSyncPreparation(
        String commandId,
        FineractProductConfiguration configuration,
        boolean executionRequired
) {
}
