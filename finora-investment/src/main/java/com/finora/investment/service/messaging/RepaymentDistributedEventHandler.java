package com.finora.investment.service.messaging;

import com.finora.investment.messaging.event.RepaymentDistributedEventData;

import java.time.Instant;
import java.util.UUID;

/**
 * Xử lý event RepaymentDistributed: ghi tiền trả nợ vào từng Note, kiểm tổng phân bổ khớp số Payment báo và
 * chuyển lại phần đã trả nhầm cho chủ cũ của Note. Mỗi event chỉ xử lý một lần theo {@code eventId}.
 */
public interface RepaymentDistributedEventHandler {

    void handle(UUID eventId, Instant occurredAt, RepaymentDistributedEventData data);
}
