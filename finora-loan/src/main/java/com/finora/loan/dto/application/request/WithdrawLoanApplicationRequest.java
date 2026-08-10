package com.finora.loan.dto.application.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record WithdrawLoanApplicationRequest(
        @NotNull @PositiveOrZero Long version,
        @Size(max = 500) String reason
) {
}
