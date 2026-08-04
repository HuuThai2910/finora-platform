package com.finora.loan.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record VersionedActionRequest(
        @NotNull @PositiveOrZero Long version
) {
}
