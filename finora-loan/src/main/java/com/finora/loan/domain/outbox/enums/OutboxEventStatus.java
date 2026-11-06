package com.finora.loan.domain.outbox.enums;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    DEAD_LETTER
}
