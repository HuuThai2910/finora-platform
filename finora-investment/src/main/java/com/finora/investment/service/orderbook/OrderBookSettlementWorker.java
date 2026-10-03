package com.finora.investment.service.orderbook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Quét định kỳ các lần khớp chờ thanh toán và các lệnh mua chờ nhả tiền.
 *
 * <p>Dùng trạng thái trong bảng thay vì gọi Payment ngay sau commit: service chết giữa chừng thì
 * lần quét sau làm tiếp, không mất lần thanh toán nào.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "finora.investment.order-book.settlement", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OrderBookSettlementWorker {

    private final OrderBookSettlementService settlementService;

    @Value("${finora.investment.order-book.settlement.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${finora.investment.order-book.settlement.delay:2000}")
    public void run() {
        try {
            settlementService.settleDue(batchSize);
            settlementService.releaseFinished(batchSize);
        } catch (RuntimeException failure) {
            log.error("Worker thanh toán sổ lệnh gặp lỗi: exceptionType={}", failure.getClass().getName());
        }
    }
}
