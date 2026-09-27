package com.finora.loan.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record DisbursementCompletedEventData(UUID sagaId, Long loanApplicationId, String contractNumber,
        Long listingId, String amount, String currency, String paymentReference, Instant completedAt) {}

