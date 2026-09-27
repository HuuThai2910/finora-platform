package com.finora.investment.service.outbox;

import java.util.UUID;

public record ClaimedInvestmentOutboxMessage(
        Long databaseId,
        UUID claimToken,
        InvestmentOutboxMessage message
) {
}
