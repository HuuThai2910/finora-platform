package com.finora.loan.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ScoringRetryRequest(
        @NotNull @PositiveOrZero Long version
) {
}
