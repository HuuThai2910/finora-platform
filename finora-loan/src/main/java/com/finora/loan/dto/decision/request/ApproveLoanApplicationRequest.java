package com.finora.loan.dto.decision.request;

import com.finora.loan.domain.decision.AdminDecisionReasonCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ApproveLoanApplicationRequest(
        @PositiveOrZero long applicationVersion,
        @NotNull @Positive Long assessmentId,
        @NotNull AdminDecisionReasonCode decisionReasonCode,
        @Size(max = 1000) String decisionReasonDetail,
        Instant contractExpiresAt
) {
}
