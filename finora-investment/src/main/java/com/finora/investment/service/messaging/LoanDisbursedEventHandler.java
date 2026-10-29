package com.finora.investment.service.messaging;

import com.finora.investment.messaging.event.LoanDisbursedEventData;

import java.time.Instant;
import java.util.UUID;

/**
 * Xử lý event LoanDisbursed: hoàn tất cam kết, kích hoạt Note, công bố quyền sở hữu và ghi ngày đáo hạn.
 */
public interface LoanDisbursedEventHandler {

    /** Idempotent theo {@code eventId}; tham chiếu listing/hợp đồng hoặc số tiền lệch thì từ chối. */
    void handle(UUID eventId, Instant occurredAt, LoanDisbursedEventData data);
}
