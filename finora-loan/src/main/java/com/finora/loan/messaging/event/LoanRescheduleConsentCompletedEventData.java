package com.finora.loan.messaging.event;

import java.time.Instant;
import java.util.UUID;

/** Kết quả biểu quyết nhất trí của toàn bộ nhà đầu tư còn nắm dư nợ. */
public record LoanRescheduleConsentCompletedEventData(
        UUID requestId,
        Long loanApplicationId,
        boolean approved,
        int requiredInvestors,
        int approvedInvestors,
        Instant resolvedAt
) {}
