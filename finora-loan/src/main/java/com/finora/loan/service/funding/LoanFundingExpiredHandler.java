package com.finora.loan.service.funding;

import com.finora.loan.messaging.event.LoanFundingExpiredEventData;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer transaction của {@code LoanFundingExpired.v1}: đóng bước chờ gọi vốn của hồ sơ.
 *
 * <p>Investment đã huỷ mọi phần vốn và tự nhả tiền giữ cho nhà đầu tư; Loan chỉ ghi kết quả để
 * borrower thấy "gọi vốn không thành công" thay vì chờ mãi, và không lập Contract.</p>
 */
public interface LoanFundingExpiredHandler {

    /** Xử lý một event trong một transaction; {@code eventId} đã xử lý thì bỏ qua. */
    void handle(UUID eventId, Instant occurredAt, LoanFundingExpiredEventData eventData);
}
