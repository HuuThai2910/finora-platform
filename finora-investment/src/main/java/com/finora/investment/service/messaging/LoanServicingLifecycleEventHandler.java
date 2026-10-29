package com.finora.investment.service.messaging;

import com.finora.investment.messaging.event.LoanDelinquencyChangedEventData;
import com.finora.investment.messaging.event.LoanRescheduledEventData;
import com.finora.investment.messaging.event.LoanSettledEventData;

import java.time.Instant;
import java.util.UUID;

/**
 * Xử lý event vòng đời khoản vay từ Loan (cơ cấu, tất toán, đổi nhóm nợ): cập nhật trạng thái khoản vay phía
 * Investment và báo cho nhà đầu tư đang giữ Note. Mỗi event chỉ xử lý một lần theo {@code eventId}.
 */
public interface LoanServicingLifecycleEventHandler {

    void rescheduled(UUID eventId, Instant receivedAt, LoanRescheduledEventData data);

    void settled(UUID eventId, Instant receivedAt, LoanSettledEventData data);

    void delinquencyChanged(UUID eventId, Instant receivedAt,
            LoanDelinquencyChangedEventData data);
}
