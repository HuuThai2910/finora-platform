package com.finora.investment.service.autoinvest;

/**
 * Quét listing đang mở mà Auto-Invest chưa xét.
 */
public interface AutoInvestWorker {

    void processPendingListings();
}
