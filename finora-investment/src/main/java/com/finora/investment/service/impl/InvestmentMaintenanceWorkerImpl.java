package com.finora.investment.service.impl;

import com.finora.investment.service.FundingMaintenanceService;
import com.finora.investment.service.InvestmentMaintenanceWorker;
import com.finora.investment.service.MarketListingService;
import com.finora.investment.service.messaging.RepaymentRedirectService;
import com.finora.investment.service.orderbook.OrderBookMaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Quét định kỳ các trạng thái không tự kết thúc: listing hết hạn gọi vốn, lệnh kẹt chờ giữ tiền,
 * khoản giữ chưa nhả, sổ lệnh của khoản vay đã tất toán và khoản trả nợ chia nhầm chủ cũ.
 *
 * <p>Mỗi bước độc lập và tự bắt lỗi: một bước hỏng (Payment sập) không chặn các bước còn lại. Mọi
 * bước đều đọc trạng thái từ bảng nên service chết giữa chừng thì lần quét sau làm tiếp.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "finora.investment.maintenance", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InvestmentMaintenanceWorkerImpl implements InvestmentMaintenanceWorker {

    private final MarketListingService listingService;
    private final FundingMaintenanceService fundingMaintenance;
    private final OrderBookMaintenanceService orderBookMaintenance;
    private final RepaymentRedirectService repaymentRedirects;

    @Value("${finora.investment.maintenance.batch-size:50}")
    private int batchSize;

    @Override
    @Scheduled(fixedDelayString = "${finora.investment.maintenance.delay:30000}")
    public void run() {
        step("closeExpiredListings", () -> listingService.closeExpiredListings(batchSize));
        step("resumeStalePendingOrders", () -> fundingMaintenance.resumeStalePendingOrders(batchSize));
        step("releasePendingHolds", () -> fundingMaintenance.releasePendingHolds(batchSize));
        step("expireStalePendingBids", () -> orderBookMaintenance.expireStalePendingBids(batchSize));
        step("closeFinishedBooks", () -> orderBookMaintenance.closeFinishedBooks(batchSize));
        step("settleRepaymentRedirects", () -> repaymentRedirects.settleDue(batchSize));
    }

    private void step(String name, java.util.function.IntSupplier action) {
        try {
            action.getAsInt();
        } catch (RuntimeException failure) {
            log.error("Bước bảo trì Investment lỗi: step={}, exceptionType={}",
                    name, failure.getClass().getName());
        }
    }
}
