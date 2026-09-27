package com.finora.loan.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record DisbursementFailedEventData(UUID sagaId, Long loanApplicationId, String contractNumber,
        String errorCode, String errorMessage, Instant failedAt) {}

