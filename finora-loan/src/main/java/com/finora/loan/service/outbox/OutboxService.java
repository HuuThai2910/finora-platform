package com.finora.loan.service.outbox;

import java.util.UUID;

/**
 * Ghi domain event cùng local transaction với aggregate; không publish mạng tại đây.
 */
public interface OutboxService {

    /** Event chỉ dùng nội bộ, không relay ra Kafka; bắt buộc chạy trong transaction đang mở. */
    UUID record(
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            Object payload
    );

    /** Chỉ event contract liên service đã duyệt mới được relay ra Kafka. */
    UUID recordForPublication(
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            Object payload
    );
}
