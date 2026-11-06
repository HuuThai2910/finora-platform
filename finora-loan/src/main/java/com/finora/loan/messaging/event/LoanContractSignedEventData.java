package com.finora.loan.messaging.event;

import com.finora.loan.domain.contract.enums.SignatureMethod;
import com.finora.loan.domain.contract.enums.SignatureProviderType;

import java.time.Instant;

public record LoanContractSignedEventData(
        String contractNumber,
        Long applicationId,
        String documentHash,
        SignatureProviderType signatureProvider,
        SignatureMethod signatureMethod,
        String signatureTransactionId,
        String signatureEvidenceHash,
        Instant signedAt
) {
}
