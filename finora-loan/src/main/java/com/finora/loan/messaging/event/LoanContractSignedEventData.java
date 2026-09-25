package com.finora.loan.messaging.event;

import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;
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
