package com.finora.loan.service.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Orchestrator gọi broker ngoài transaction rồi commit kết quả ở transaction kế tiếp. */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayService {

    private final OutboxRelayStateService stateService;
    private final ObjectProvider<OutboxPublisher> publisherProvider;

    public void publish(Long id) {
        ClaimedOutboxMessage claimed = stateService.claim(id);
        if (claimed == null) {
            return;
        }
        try {
            OutboxPublisher publisher = publisherProvider.getIfAvailable();
            if (publisher == null) {
                throw new OutboxPublishException(
                        "OUTBOX_PUBLISHER_UNAVAILABLE",
                        "Chưa cấu hình transport publisher cho outbox",
                        true,
                        null
                );
            }
            publisher.publish(claimed.message());
            stateService.markPublished(claimed.databaseId(), claimed.claimToken());
        } catch (OutboxPublishException failure) {
            stateService.markFailed(
                    claimed.databaseId(), claimed.claimToken(), failure.errorCode(),
                    failure.getMessage(), failure.retryable());
            log.warn("Không publish được outbox event: eventId={}, eventType={}, code={}, retryable={}",
                    claimed.message().eventId(), claimed.message().eventType(),
                    failure.errorCode(), failure.retryable());
        } catch (RuntimeException failure) {
            stateService.markFailed(
                    claimed.databaseId(), claimed.claimToken(), "OUTBOX_TRANSPORT_FAILURE",
                    failure.getClass().getSimpleName(), true);
            log.warn("Transport outbox lỗi chưa phân loại: eventId={}, eventType={}, exceptionType={}",
                    claimed.message().eventId(), claimed.message().eventType(), failure.getClass().getName());
        }
    }
}
