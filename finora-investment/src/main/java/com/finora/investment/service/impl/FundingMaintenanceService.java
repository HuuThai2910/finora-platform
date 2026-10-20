package com.finora.investment.service.impl;

import com.finora.investment.client.PaymentCallException;
import com.finora.investment.client.PaymentClient;
import com.finora.investment.domain.order.InvestmentOrder;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.InvestmentOrderRepository;
import com.finora.investment.service.FundingService;
import com.finora.investment.service.OrderTransactionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Đối soát luồng gọi vốn (F04): những trạng thái chỉ kết thúc được khi có ai đó làm tiếp.
 *
 * <ul>
 *   <li>Lệnh kẹt {@code PENDING_FUNDS} vì Payment không trả lời lần giữ tiền đầu: làm tiếp từ bước
 *       giữ tiền, đúng điều thông báo "kiểm tra lại lệnh sau ít phút" đã hứa với nhà đầu tư.</li>
 *   <li>Khoản giữ của lệnh đã kết thúc mà chưa nhả được (huỷ do hết hạn gọi vốn, bị từ chối sau khi
 *       đã giữ, lần nhả trước lỗi mạng): gọi {@code release} tới khi Payment xác nhận.</li>
 * </ul>
 *
 * <p>Không có transaction ở tầng này: chờ Payment nằm ngoài, ghi database đi qua các transaction
 * ngắn của {@link OrderTransactionService}.</p>
 */
@Slf4j
@Service
public class FundingMaintenanceService {

    private final InvestmentOrderRepository orderRepository;
    private final FundingService fundingService;
    private final OrderTransactionService transactions;
    private final PaymentClient paymentClient;
    private final Clock clock;
    private final Duration pendingFundsTimeout;

    public FundingMaintenanceService(
            InvestmentOrderRepository orderRepository,
            FundingService fundingService,
            OrderTransactionService transactions,
            PaymentClient paymentClient,
            Clock clock,
            @Value("${finora.investment.maintenance.pending-funds-timeout:2m}") Duration pendingFundsTimeout) {
        this.orderRepository = orderRepository;
        this.fundingService = fundingService;
        this.transactions = transactions;
        this.paymentClient = paymentClient;
        this.clock = clock;
        this.pendingFundsTimeout = pendingFundsTimeout;
    }

    /**
     * Làm tiếp lệnh kẹt {@code PENDING_FUNDS} quá {@code pending-funds-timeout}. Chỉ chạm lệnh đủ cũ
     * để không giành việc với request của người dùng đang chạy dở.
     *
     * @return số lệnh đã có kết quả cuối (cam kết hoặc bị từ chối)
     */
    public int resumeStalePendingOrders(int batchSize) {
        int resolved = 0;
        Instant now = clock.instant();
        var before = now.minus(pendingFundsTimeout);
        for (InvestmentOrder order : orderRepository.findPendingDue(before, now, PageRequest.of(0, batchSize))) {
            try {
                fundingService.resumePendingOrder(order.getId());
                resolved++;
            } catch (InvestmentDomainException failure) {
                // PAYMENT_UNAVAILABLE: Payment vẫn chưa trả lời; lùi lượt sau để lệnh lỗi không chặn lệnh khác (R3).
                transactions.recordPendingFailure(order.getId(), now);
                log.warn("Chưa đối soát được lệnh chờ giữ tiền: orderReference={}, code={}",
                        order.getOrderReference(), failure.getCode());
            } catch (RuntimeException failure) {
                transactions.recordPendingFailure(order.getId(), now);
                log.error("Lỗi khi đối soát lệnh chờ giữ tiền: orderReference={}, exceptionType={}",
                        order.getOrderReference(), failure.getClass().getName());
            }
        }
        return resolved;
    }

    /** Nhả khoản giữ của lệnh đã kết thúc; Payment idempotent theo mã lệnh nên gọi lại an toàn. */
    public int releasePendingHolds(int batchSize) {
        int released = 0;
        Instant now = clock.instant();
        for (InvestmentOrder order : orderRepository.findReleaseDue(now, PageRequest.of(0, batchSize))) {
            try {
                paymentClient.release(order.getPaymentHoldReference(), order.getOrderReference());
            } catch (PaymentCallException failure) {
                // Lỗi chắc chắn (khoản giữ đã vào giải ngân, không tìm thấy, lệch mã lệnh) thì dừng cho vận hành; còn lại
                // thử lại theo lịch lùi dần (R3).
                transactions.recordReleaseFailure(order.getId(), failure.getCode(), failure.isRetryable(), now);
                if (failure.isRetryable()) {
                    log.warn("Chưa nhả được tiền giữ của lệnh đặt vốn: orderReference={}, code={}",
                            order.getOrderReference(), failure.getCode());
                } else {
                    log.error("Dừng nhả tiền giữ, cần vận hành kiểm tra: orderReference={}, code={}",
                            order.getOrderReference(), failure.getCode());
                }
                continue;
            } catch (RuntimeException failure) {
                transactions.recordReleaseFailure(order.getId(), "PAYMENT_UNAVAILABLE", true, now);
                log.warn("Chưa nhả được tiền giữ của lệnh đặt vốn: orderReference={}, exceptionType={}",
                        order.getOrderReference(), failure.getClass().getName());
                continue;
            }
            transactions.markHoldReleased(order.getId());
            released++;
            log.info("Đã nhả tiền giữ của lệnh đặt vốn: orderReference={}", order.getOrderReference());
        }
        return released;
    }
}
