package com.finora.investment.service.outbox.impl;

import com.finora.investment.service.outbox.ClaimedInvestmentOutboxMessage;
import com.finora.investment.service.outbox.InvestmentOutboxPublishException;
import com.finora.investment.service.outbox.InvestmentOutboxPublisher;
import com.finora.investment.service.outbox.InvestmentOutboxRelayService;
import com.finora.investment.service.outbox.InvestmentOutboxStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Gửi một event outbox lên Kafka và ghi lại kết quả (đã gửi hoặc lỗi kèm có thử lại được không).
 * <p>
 * Nhận event bằng claim token trước khi gửi; chưa có publisher (chưa cấu hình Kafka) hoặc lỗi chưa phân loại đều
 * ghi là lỗi thử lại được.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvestmentOutboxRelayServiceImpl implements InvestmentOutboxRelayService {

    private final InvestmentOutboxStateService investmentOutboxStateService;
    private final ObjectProvider<InvestmentOutboxPublisher> investmentOutboxPublisherProvider;

    @Override
    public void publish(Long id) {
        ClaimedInvestmentOutboxMessage claimed = investmentOutboxStateService.claim(id);
        if (claimed == null) {
            return;
        }
        try {
            InvestmentOutboxPublisher publisher = investmentOutboxPublisherProvider.getIfAvailable();
            if (publisher == null) {
                throw new InvestmentOutboxPublishException(
                        "OUTBOX_PUBLISHER_UNAVAILABLE", "Chưa cấu hình Kafka publisher", true, null);
            }
            publisher.publish(claimed.message());
            investmentOutboxStateService.markPublished(id, claimed.claimToken());
        } catch (InvestmentOutboxPublishException failure) {
            investmentOutboxStateService.markFailed(id, claimed.claimToken(), failure.getErrorCode(),
                    failure.getMessage(), failure.isRetryable());
            log.warn("Không publish được Investment outbox: eventId={}, code={}, retryable={}",
                    claimed.message().eventId(), failure.getErrorCode(), failure.isRetryable());
        } catch (RuntimeException failure) {
            investmentOutboxStateService.markFailed(id, claimed.claimToken(), "OUTBOX_TRANSPORT_FAILURE",
                    failure.getClass().getSimpleName(), true);
            log.warn("Investment outbox lỗi chưa phân loại: eventId={}, exceptionType={}",
                    claimed.message().eventId(), failure.getClass().getName());
        }
    }
}
