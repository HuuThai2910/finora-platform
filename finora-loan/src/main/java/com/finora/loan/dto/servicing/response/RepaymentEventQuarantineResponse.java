package com.finora.loan.dto.servicing.response;

import com.finora.loan.domain.servicing.RepaymentEventQuarantine;
import com.finora.loan.domain.servicing.RepaymentEventQuarantineStatus;
import java.time.Instant;
import java.util.UUID;

public record RepaymentEventQuarantineResponse(UUID eventId, Long loanApplicationId, UUID repaymentId,
        RepaymentEventQuarantineStatus status, String reasonCode, int attemptCount,
        Instant receivedAt, Instant lastAttemptAt, Instant resolvedAt) {
    public static RepaymentEventQuarantineResponse from(RepaymentEventQuarantine value) {
        return new RepaymentEventQuarantineResponse(value.getEventId(), value.getLoanApplicationId(),
                value.getRepaymentId(), value.getStatus(), value.getReasonCode(), value.getAttemptCount(),
                value.getReceivedAt(), value.getLastAttemptAt(), value.getResolvedAt());
    }
}
