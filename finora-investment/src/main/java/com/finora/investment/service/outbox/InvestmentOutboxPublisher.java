package com.finora.investment.service.outbox;

public interface InvestmentOutboxPublisher {
    void publish(InvestmentOutboxMessage message);
}
