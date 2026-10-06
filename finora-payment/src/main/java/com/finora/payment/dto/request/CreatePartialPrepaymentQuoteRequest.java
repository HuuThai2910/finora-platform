package com.finora.payment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreatePartialPrepaymentQuoteRequest(@NotNull Long loanApplicationId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal prepaidPrincipal) {}
