package com.finora.investment.messaging.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Projection event dành cho Notification. Investment là service biết chủ Note,
 * nên thực hiện fan-out theo investor mà không để Notification gọi ngược N+1.
 */
public record InvestorServicingNotificationEventData(
        String changeType,
        Long loanApplicationId,
        String loanNumber,
        int daysPastDue,
        int debtGroup,
        String amount,
        LocalDate maturityDate,
        boolean externalPushRequired,
        Instant changedAt,
        List<Recipient> recipients
) {
    public record Recipient(Long noteId, String noteNumber, String investorId) {}
}
