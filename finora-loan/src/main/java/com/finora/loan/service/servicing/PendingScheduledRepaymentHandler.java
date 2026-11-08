package com.finora.loan.service.servicing;

import com.finora.loan.messaging.event.RepaymentCancelledEventData;
import com.finora.loan.messaging.event.RepaymentCollectedEventData;

import java.time.Instant;
import java.util.UUID;

public interface PendingScheduledRepaymentHandler {
    void collected(UUID eventId, Instant occurredAt, RepaymentCollectedEventData data);
    void cancelled(UUID eventId, Instant occurredAt, RepaymentCancelledEventData data);
}
