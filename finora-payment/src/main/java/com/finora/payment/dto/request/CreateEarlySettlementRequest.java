package com.finora.payment.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateEarlySettlementRequest(@NotNull UUID quoteId) {}
