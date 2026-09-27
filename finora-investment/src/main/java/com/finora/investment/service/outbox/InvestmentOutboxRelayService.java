package com.finora.investment.service.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvestmentOutboxRelayService {

    private final InvestmentOutboxStateService stateService;
    private final ObjectProvider<InvestmentOutboxPublisher> publisherProvider;

    public void publish(Long id) {
        ClaimedInvestmentOutboxMessage claimed = stateService.claim(id);
        if (claimed == null) {
            return;
        }
        try {
            InvestmentOutboxPublisher publisher = publisherProvider.getIfAvailable();
            if (publisher == null) {
                throw new InvestmentOutboxPublishException(
                        "OUTBOX_PUBLISHER_UNAVAILABLE", "Chưa cấu hình Kafka publisher", true, null);
            }
            publisher.publish(claimed.message());
            stateService.markPublished(id, claimed.claimToken());
        } catch (InvestmentOutboxPublishException failure) {
            stateService.markFailed(id, claimed.claimToken(), failure.getErrorCode(),
                    failure.getMessage(), failure.isRetryable());
            log.warn("Không publish được Investment outbox: eventId={}, code={}, retryable={}",
                    claimed.message().eventId(), failure.getErrorCode(), failure.isRetryable());
        } catch (RuntimeException failure) {
            stateService.markFailed(id, claimed.claimToken(), "OUTBOX_TRANSPORT_FAILURE",
                    failure.getClass().getSimpleName(), true);
            log.warn("Investment outbox lỗi chưa phân loại: eventId={}, exceptionType={}",
                    claimed.message().eventId(), failure.getClass().getName());
        }
    }
}
