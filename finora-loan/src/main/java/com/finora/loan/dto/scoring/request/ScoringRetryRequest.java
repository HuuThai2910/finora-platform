package com.finora.loan.dto.scoring.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ScoringRetryRequest(
        @NotNull @PositiveOrZero Long version
) {
}
