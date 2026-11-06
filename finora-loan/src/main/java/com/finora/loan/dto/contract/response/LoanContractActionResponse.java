package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.contract.enums.LoanContractStatus;
import com.finora.loan.domain.contract.enums.SignatureMethod;
import com.finora.loan.domain.contract.enums.SignatureProviderType;

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
