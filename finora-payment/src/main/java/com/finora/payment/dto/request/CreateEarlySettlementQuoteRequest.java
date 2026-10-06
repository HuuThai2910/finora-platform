package com.finora.payment.dto.request;

import jakarta.validation.constraints.NotNull;

public record CreateEarlySettlementQuoteRequest(@NotNull Long loanApplicationId) {}
