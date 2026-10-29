package com.finora.investment.service.messaging;

import com.finora.investment.messaging.event.LoanFundingCancelledEventData;

import java.time.Instant;
import java.util.UUID;

/**
 * Bồi hoàn cho đợt gọi vốn đã đủ vốn nhưng Loan xác nhận sẽ không giải ngân.
 */
public interface LoanFundingCancelledHandler {

    void handle(UUID eventId, Instant occurredAt, LoanFundingCancelledEventData data);
}
