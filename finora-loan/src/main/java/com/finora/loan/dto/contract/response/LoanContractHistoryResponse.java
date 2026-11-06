package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.contract.enums.LoanContractStatus;

import java.time.Instant;

public record LoanContractHistoryResponse(
        Long id,
        LoanContractStatus fromStatus,
        LoanContractStatus toStatus,
        String reasonCode,
        ActorType actorType,
        String actorId,
        Instant occurredAt
) {
}
