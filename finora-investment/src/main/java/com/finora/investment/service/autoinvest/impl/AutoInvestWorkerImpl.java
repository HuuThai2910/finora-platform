package com.finora.investment.service.autoinvest.impl;

import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.service.autoinvest.AutoInvestMatcher;
import com.finora.investment.service.autoinvest.AutoInvestWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Quét listing đang mở mà Auto-Invest chưa xét.
 *
 * <p>Dùng cờ {@code auto_invest_processed_at} trên listing thay vì nghe event trực tiếp: service
 * chết giữa chừng thì lần quét sau làm tiếp, và listing mở bằng đường nào cũng được xét.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "finora.investment.auto-invest", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AutoInvestWorkerImpl implements AutoInvestWorker {

    private final MarketListingRepository marketListingRepository;
    private final AutoInvestMatcher autoInvestMatcher;
    private final int batchSize;

    public AutoInvestWorkerImpl(
            MarketListingRepository marketListingRepository,
            AutoInvestMatcher autoInvestMatcher,
            @Value("${finora.investment.auto-invest.batch-size:20}") int batchSize) {
        this.marketListingRepository = marketListingRepository;
        this.autoInvestMatcher = autoInvestMatcher;
        this.batchSize = batchSize;
    }

    @Override
    @Scheduled(fixedDelayString = "${finora.investment.auto-invest.delay:5000}")
    public void processPendingListings() {
        for (Long listingId : marketListingRepository.findAutoInvestPendingIds(PageRequest.of(0, batchSize))) {
            try {
                autoInvestMatcher.processListing(listingId);
            } catch (RuntimeException failure) {
                log.error("Auto-Invest chưa xét được listing: listingId={}, exceptionType={}",
                        listingId, failure.getClass().getName());
            }
        }
    }
}
