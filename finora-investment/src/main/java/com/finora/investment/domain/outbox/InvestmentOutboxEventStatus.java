package com.finora.investment.domain.outbox;

public enum InvestmentOutboxEventStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    DEAD_LETTER
}
