package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.contract.LoanContractStatus;
import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;
import java.time.Instant;

public record LoanContractActionResponse(
        String contractNumber,
        LoanContractStatus status,
        Long version,
        String documentHash,
        SignatureProviderType signatureProvider,
        SignatureMethod signatureMethod,
        String signatureTransactionId,
        String signatureEvidenceHash,
        String actorId,
        Instant actedAt
) {
}
