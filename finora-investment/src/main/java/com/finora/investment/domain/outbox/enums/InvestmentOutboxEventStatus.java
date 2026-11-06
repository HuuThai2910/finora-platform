package com.finora.investment.domain.outbox.enums;

public enum InvestmentOutboxEventStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    DEAD_LETTER
}
