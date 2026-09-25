package com.finora.loan.service.outbox;

/**
 * Cổng transport. Implementation phải idempotent theo eventId vì outbox đảm bảo at-least-once,
 * không hứa exactly-once xuyên DB và broker.
 */
public interface OutboxPublisher {

    void publish(OutboxMessage message);
}
