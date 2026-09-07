package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.contract.ContractPdfArtifactType;

public record LoanContractPdfResponse(
        ContractPdfArtifactType artifactType,
        String documentVersion,
        String contentType,
        String contentHash,
        Integer contentLength,
        String downloadPath
) {
}
