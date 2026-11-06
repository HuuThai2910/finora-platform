package com.finora.loan.service.servicing;

import com.finora.loan.messaging.event.RepaymentDistributedEventData;

import java.time.Instant;
import java.util.UUID;

/**
 * Xử lý event {@code RepaymentDistributed} của Payment: cập nhật projection, hồ sơ thu hồi và phát event tất toán/đổi
 * nhóm nợ.
 */
public interface RepaymentDistributedHandler {

    /** Xử lý một event trong một transaction; {@code eventId} đã xử lý thì bỏ qua. */
    void handle(UUID eventId, Instant receivedAt, RepaymentDistributedEventData eventData);

    /** Chạy lại một event trong quarantine; event đã giải quyết thì bỏ qua. */
    void replay(UUID eventId);
}
