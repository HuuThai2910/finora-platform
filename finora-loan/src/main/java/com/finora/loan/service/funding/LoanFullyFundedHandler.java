package com.finora.loan.service.funding;

import com.finora.loan.messaging.event.LoanFullyFundedEventData;

import java.time.Instant;
import java.util.UUID;

/**
 * Xử lý event {@code LoanFullyFunded}: khóa funding snapshot, lập một Contract chung và ghi marker cùng commit.
 */
public interface LoanFullyFundedHandler {

    /**
     * Xử lý một event trong một transaction; {@code eventId} đã xử lý thì bỏ qua.
     *
     * <p>Khi event sẽ lập (hoặc phát hành lại) hợp đồng, danh tính bên vay được đọc từ finora-user trước transaction.
     * Không đọc được thì ném {@code BorrowerIdentityUnavailableException}, không ghi gì, để consumer thử lại.</p>
     */
    void handle(UUID eventId, Instant occurredAt, LoanFullyFundedEventData eventData);
}
