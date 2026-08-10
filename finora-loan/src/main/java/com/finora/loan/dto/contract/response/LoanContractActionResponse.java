package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.contract.LoanContractStatus;
import java.time.Instant;

public record LoanContractActionResponse(
        String contractNumber,
        LoanContractStatus status,
        Long version,
        String documentHash,
        String actorId,
        Instant actedAt
) {
}
