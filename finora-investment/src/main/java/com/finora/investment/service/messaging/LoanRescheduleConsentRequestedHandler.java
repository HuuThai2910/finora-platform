package com.finora.investment.service.messaging;

import com.finora.investment.messaging.event.LoanRescheduleConsentRequestedEventData;

import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot chủ Note hiện tại; chuyển nhượng sau thời điểm này không thay phiếu đã phát hành.
 */
public interface LoanRescheduleConsentRequestedHandler {

    void handle(UUID eventId, Instant occurredAt, LoanRescheduleConsentRequestedEventData data);
}
