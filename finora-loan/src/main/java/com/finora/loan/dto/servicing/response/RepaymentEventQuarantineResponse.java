package com.finora.loan.dto.servicing.response;

import com.finora.loan.domain.servicing.entity.RepaymentEventQuarantine;
import com.finora.loan.domain.servicing.enums.RepaymentEventQuarantineStatus;
import com.finora.loan.dto.decision.response.ApplicationNumberResponse;

import java.time.Instant;
import java.util.UUID;

public record RepaymentEventQuarantineResponse(UUID eventId, Long loanApplicationId, UUID repaymentId,
        RepaymentEventQuarantineStatus status, String reasonCode, int attemptCount,
        Instant receivedAt, Instant lastAttemptAt, Instant resolvedAt,
        /** Mã hồ sơ {@code LA-…}; {@code null} khi event trỏ tới hồ sơ không có (chính là lý do bị cách ly). */
        String applicationNumber,
        /** Mã hiển thị {@code HS-…} của hồ sơ; {@code null} cùng lúc với {@code applicationNumber}. */
        String applicationCode) {
    public static RepaymentEventQuarantineResponse from(RepaymentEventQuarantine value) {
        return from(value, null);
    }

    public static RepaymentEventQuarantineResponse from(
            RepaymentEventQuarantine value, ApplicationNumberResponse reference) {
        return new RepaymentEventQuarantineResponse(value.getEventId(), value.getLoanApplicationId(),
                value.getRepaymentId(), value.getStatus(), value.getReasonCode(), value.getAttemptCount(),
                value.getReceivedAt(), value.getLastAttemptAt(), value.getResolvedAt(),
                reference == null ? null : reference.applicationNumber(),
                reference == null ? null : reference.applicationCode());
    }
}
