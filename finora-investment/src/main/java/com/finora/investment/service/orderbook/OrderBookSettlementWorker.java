package com.finora.investment.service.orderbook;

/**
 * Quét định kỳ các lần khớp chờ thanh toán và các lệnh mua chờ nhả tiền.
 */
public interface OrderBookSettlementWorker {

    void run();
}
