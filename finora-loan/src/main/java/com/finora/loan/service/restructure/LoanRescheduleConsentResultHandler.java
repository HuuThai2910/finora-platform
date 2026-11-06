package com.finora.loan.service.restructure;

import com.finora.loan.messaging.event.LoanRescheduleConsentCompletedEventData;

import java.time.Instant;
import java.util.UUID;

/**
 * Áp kết quả đồng ý cơ cấu của các chủ Note (event {@code LoanRescheduleConsentCompleted} từ Investment).
 */
public interface LoanRescheduleConsentResultHandler {

    /**
     * Đồng ý đủ thì khoản vay chuyển sang cơ cấu và yêu cầu mở bước gọi Fineract; có người từ chối thì yêu cầu dừng ở
     * trạng thái nhà đầu tư từ chối.
     */
    void handle(UUID eventId, Instant occurredAt, LoanRescheduleConsentCompletedEventData data);
}
