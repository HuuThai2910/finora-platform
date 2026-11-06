package com.finora.investment.service.outbox;

/**
 * Đẩy một event outbox lên Kafka; lỗi ném {@link InvestmentOutboxPublishException} kèm mã và cờ thử lại được.
 */
public interface InvestmentOutboxPublisher {
    void publish(InvestmentOutboxMessage message);
}
