package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.contract.LoanContractStatus;
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
