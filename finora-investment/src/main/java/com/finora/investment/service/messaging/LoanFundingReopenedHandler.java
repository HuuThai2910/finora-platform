package com.finora.investment.service.messaging;

import com.finora.investment.messaging.event.LoanFundingReopenedEventData;

import java.time.Instant;
import java.util.UUID;

/**
 * Mở lại listing đã đủ vốn khi nhà đầu tư chưa ký rời hợp đồng (FUNDING-REOPEN-001).
 */
public interface LoanFundingReopenedHandler {

    void handle(UUID eventId, Instant occurredAt, LoanFundingReopenedEventData data);
}
