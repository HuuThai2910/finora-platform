package com.finora.investment.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record LoanRescheduleConsentCompletedEventData(
        UUID requestId, Long loanApplicationId, boolean approved,
        int requiredInvestors, int approvedInvestors, Instant resolvedAt
) {}
