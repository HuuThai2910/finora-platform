package com.finora.loan.dto.application.response;

import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;

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
