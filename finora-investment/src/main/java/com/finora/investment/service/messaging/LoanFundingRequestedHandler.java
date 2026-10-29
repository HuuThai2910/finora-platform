package com.finora.investment.service.messaging;

import com.finora.investment.messaging.event.LoanFundingRequestedEventData;

import java.time.Instant;
import java.util.UUID;

/** Consumer transaction: tạo listing và ghi processed marker cùng thành công hoặc cùng rollback. */
public interface LoanFundingRequestedHandler {

    void handle(UUID eventId, Instant occurredAt, LoanFundingRequestedEventData data);
}
