package com.finora.loan.integration.fineract;

public record FineractProductCreationResult(
        Long resourceId,
        String responseSnapshotJson
) {
}
