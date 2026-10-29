package com.finora.investment.service.outbox;

import java.util.UUID;

/**
 * Ghi event vào bảng outbox trong cùng transaction với thay đổi nghiệp vụ, để event không mất khi Kafka lỗi.
 */
public interface InvestmentOutboxService {

    /** Phải gọi trong transaction đang mở ({@code MANDATORY}); trả {@code eventId} vừa sinh. */
    UUID record(
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            Object payload
    );
}
