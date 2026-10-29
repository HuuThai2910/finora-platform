package com.finora.investment.service.outbox;

/**
 * Gửi một event outbox lên Kafka và ghi lại kết quả (đã gửi hoặc lỗi kèm có thử lại được không).
 */
public interface InvestmentOutboxRelayService {

    void publish(Long id);
}
