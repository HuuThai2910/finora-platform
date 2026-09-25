package com.finora.loan.service.outbox;

import com.finora.loan.config.OutboxProperties;
import com.finora.loan.domain.outbox.OutboxEvent;
import com.finora.loan.domain.outbox.OutboxEventStatus;
import com.finora.loan.repository.outbox.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mỗi thay đổi trạng thái relay là một transaction ngắn, tách khỏi thao tác broker. */
@Service
@RequiredArgsConstructor
public class OutboxRelayStateService {

    private final OutboxEventRepository repository;
    private final OutboxProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        Instant now = clock.instant();
        return repository.findDueIds(
                OutboxEventStatus.PENDING,
                OutboxEventStatus.PROCESSING,
                now,
                now.minus(properties.processingLease()),
                PageRequest.of(0, properties.batchSize())
        );
    }

    @Transactional
    public ClaimedOutboxMessage claim(Long id) {
        OutboxEvent event = repository.findByIdForUpdate(id).orElse(null);
        if (event == null) {
            return null;
        }
        Instant now = clock.instant();
        Instant leaseExpiredBefore = now.minus(properties.processingLease());
        if (event.getStatus() == OutboxEventStatus.PROCESSING
                && event.getProcessingStartedAt() != null
                && !event.getProcessingStartedAt().isAfter(leaseExpiredBefore)
                && event.getAttemptCount() >= properties.maxAttempts()) {
            event.markPublishFailure(
                    "OUTBOX_PROCESSING_LEASE_EXHAUSTED",
                    "Relay đã mất processing lease quá số lần cho phép",
                    false,
                    properties.maxAttempts(),
                    now,
                    event.getProcessingToken(),
                    now
            );
            repository.saveAndFlush(event);
            return null;
        }
        UUID claimToken = UUID.randomUUID();
        if (!event.claim(now, leaseExpiredBefore, claimToken)) {
            return null;
        }
        repository.saveAndFlush(event);
        return new ClaimedOutboxMessage(event.getId(), claimToken, new OutboxMessage(
                event.getEventId(), event.getAggregateType(), event.getAggregateId(),
                event.getEventType(), event.getEventVersion(), event.getPayloadJson(),
                event.getTraceId(), event.getCreatedAt()
        ));
    }

    @Transactional
    public void markPublished(Long id, UUID claimToken) {
        OutboxEvent event = repository.findByIdForUpdate(id).orElse(null);
        if (event == null) {
            return;
        }
        event.markPublished(claimToken, clock.instant());
        repository.saveAndFlush(event);
    }

    @Transactional
    public void markFailed(
            Long id,
            UUID claimToken,
            String errorCode,
            String errorDetail,
            boolean retryable
    ) {
        OutboxEvent event = repository.findByIdForUpdate(id).orElse(null);
        if (event == null) {
            return;
        }
        Instant now = clock.instant();
        event.markPublishFailure(
                errorCode,
                errorDetail,
                retryable,
                properties.maxAttempts(),
                now.plus(backoff(event.getAttemptCount())),
                claimToken,
                now
        );
        repository.saveAndFlush(event);
    }

    private Duration backoff(int attemptCount) {
        Duration delay = properties.retryBackoff();
        for (int attempt = 1; attempt < attemptCount && delay.compareTo(properties.maximumBackoff()) < 0; attempt++) {
            Duration doubled;
            try {
                doubled = delay.multipliedBy(2);
            } catch (ArithmeticException overflow) {
                return properties.maximumBackoff();
            }
            delay = doubled.compareTo(properties.maximumBackoff()) > 0
                    ? properties.maximumBackoff()
                    : doubled;
        }
        return delay;
    }
}
