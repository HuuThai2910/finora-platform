package com.finora.loan.integration.fineract.contract;

public record FineractProductCreationResult(
        Long resourceId,
        String responseSnapshotJson
) {
}
