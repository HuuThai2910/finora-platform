package com.finora.loan.dto.collection.request;

import com.finora.loan.domain.collection.CollectionActionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateCollectionActionRequest(
        @NotNull CollectionActionType actionType,
        @Size(max = 500) String note,
        LocalDate promiseDate,
        @DecimalMin(value = "0.01") @Digits(integer = 16, fraction = 2) BigDecimal promiseAmount
) {}

