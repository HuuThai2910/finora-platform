package com.finora.loan.service.outbox.impl;

import com.finora.loan.service.outbox.ClaimedOutboxMessage;
import com.finora.loan.service.outbox.OutboxPublishException;
import com.finora.loan.service.outbox.OutboxPublisher;
import com.finora.loan.service.outbox.OutboxRelayService;
import com.finora.loan.service.outbox.OutboxRelayStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Orchestrator gọi broker ngoài transaction rồi commit kết quả ở transaction kế tiếp.
 * <p>
 * Publisher lấy qua {@link ObjectProvider}: chưa cấu hình transport thì event được ghi lỗi retry được
 * ({@code OUTBOX_PUBLISHER_UNAVAILABLE}); lỗi transport chưa phân loại cũng retry được.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayServiceImpl implements OutboxRelayService {

    private final OutboxRelayStateService outboxRelayStateService;
    private final ObjectProvider<OutboxPublisher> outboxPublisherProvider;

    @Override
    public void publish(Long id) {
        ClaimedOutboxMessage claimed = outboxRelayStateService.claim(id);
        if (claimed == null) {
            return;
        }
        try {
            OutboxPublisher publisher = outboxPublisherProvider.getIfAvailable();
            if (publisher == null) {
                throw new OutboxPublishException(
                        "OUTBOX_PUBLISHER_UNAVAILABLE",
                        "Chưa cấu hình transport publisher cho outbox",
                        true,
                        null
                );
            }
            publisher.publish(claimed.message());
            outboxRelayStateService.markPublished(claimed.databaseId(), claimed.claimToken());
        } catch (OutboxPublishException failure) {
            outboxRelayStateService.markFailed(
                    claimed.databaseId(), claimed.claimToken(), failure.errorCode(),
                    failure.getMessage(), failure.retryable());
            log.warn("Không publish được outbox event: eventId={}, eventType={}, code={}, retryable={}",
                    claimed.message().eventId(), claimed.message().eventType(),
                    failure.errorCode(), failure.retryable());
        } catch (RuntimeException failure) {
            outboxRelayStateService.markFailed(
                    claimed.databaseId(), claimed.claimToken(), "OUTBOX_TRANSPORT_FAILURE",
                    failure.getClass().getSimpleName(), true);
            log.warn("Transport outbox lỗi chưa phân loại: eventId={}, eventType={}, exceptionType={}",
                    claimed.message().eventId(), claimed.message().eventType(), failure.getClass().getName());
        }
    }
}
