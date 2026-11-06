package com.finora.loan.service.outbox;

/**
 * Worker định kỳ relay outbox event đến hạn ra transport (chỉ chạy khi bật {@code finora.loan.outbox.publisher-enabled}).
 */
public interface OutboxRelayWorker {

    /** Một vòng: relay lần lượt các event đến hạn, event lỗi không chặn event sau. */
    void publishDueEvents();
}
