package com.finora.loan.dto.response;

import com.finora.loan.domain.ActorType;
import com.finora.loan.domain.LoanApplicationStatus;

import java.time.Instant;

public record LoanApplicationHistoryResponse(
        Long id,
        LoanApplicationStatus fromStatus,
        LoanApplicationStatus toStatus,
        String reasonCode,
        String reasonDetail,
        ActorType actorType,
        String actorId,
        Instant createdAt
) {
}
