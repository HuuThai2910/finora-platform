package com.finora.loan.dto.decision.request;

import com.finora.loan.domain.decision.AdminDecisionReasonCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RejectLoanApplicationRequest(
        @PositiveOrZero long applicationVersion,
        @Positive Long assessmentId,
        @NotNull AdminDecisionReasonCode reasonCode,
        @Size(max = 1000) String reasonDetail
) {
}
