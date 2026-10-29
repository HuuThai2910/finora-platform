package com.finora.investment.service.outbox;

/**
 * Worker định kỳ gửi các event outbox đến hạn; chỉ chạy khi đã cấu hình Kafka publisher.
 */
public interface InvestmentOutboxRelayWorker {

    void publishDueEvents();
}
